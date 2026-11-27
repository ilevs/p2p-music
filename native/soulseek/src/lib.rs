//! JNI bridge for the `SoulseekNative` object (`engine/SoulseekNative.kt`) to soulseek-rs-lib. Every call blocks; Kotlin calls them off the main thread.

use jni::JNIEnv;
use jni::objects::{JObject, JString};
use jni::sys::{jint, jlong, jstring};
use soulseek_rs::{Client, ClientSettings, DownloadStatus};
use std::collections::HashMap;
use std::fmt::Write;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, Mutex, RwLock};
use std::time::Duration;

const QUEUED: i64 = -1;
const FAILED: i64 = -2;

static CLIENT: RwLock<Option<Client>> = RwLock::new(None);
static DOWNLOADS: Mutex<Option<HashMap<i64, Download>>> = Mutex::new(None);
static NEXT_ID: AtomicI64 = AtomicI64::new(1);

struct Download {
    username: String,
    filename: String,
    // Bytes on disk, or QUEUED / FAILED.
    state: Arc<AtomicI64>,
}

fn string(env: &mut JNIEnv, value: &JString) -> String {
    env.get_string(value).map(Into::into).unwrap_or_default()
}

fn java(env: &mut JNIEnv, value: &str) -> jstring {
    env.new_string(value)
        .map_or(std::ptr::null_mut(), JString::into_raw)
}

/// Runs `body`, turning a panic into `fallback` so it never unwinds into the JVM.
fn guard<T>(fallback: T, body: impl FnOnce() -> T) -> T {
    catch_unwind(AssertUnwindSafe(body)).unwrap_or(fallback)
}

/// Logs in, sharing `share_dir` back. Returns null on success, else why it failed.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_ilevs_p2pmusic_engine_SoulseekNative_login<'a>(
    mut env: JNIEnv<'a>,
    _this: JObject<'a>,
    username: JString<'a>,
    password: JString<'a>,
    share_dir: JString<'a>,
    listen_port: jint,
) -> jstring {
    let username = string(&mut env, &username);
    let password = string(&mut env, &password);
    let share_dir = string(&mut env, &share_dir);
    let error = guard(Some("crash in soulseek login".to_string()), || {
        let mut settings = ClientSettings::new(username, password);
        settings.shared_directories = vec![share_dir];
        settings.listen_port = u16::try_from(listen_port).unwrap_or(settings.listen_port);
        let mut client = Client::with_settings(settings);
        let result = client.connect().and_then(|()| client.login());
        match result {
            Ok(true) => {
                let mut slot = CLIENT
                    .write()
                    .unwrap_or_else(std::sync::PoisonError::into_inner);
                if let Some(mut old) = slot.replace(client) {
                    old.disconnect();
                }
                None
            }
            Ok(false) => {
                client.disconnect();
                Some("login rejected".to_string())
            }
            Err(e) => {
                client.disconnect();
                Some(e.to_string())
            }
        }
    });
    error.map_or(std::ptr::null_mut(), |e| java(&mut env, &e))
}

/// True while the server session is alive; after a network change it is gone and Kotlin logs in again.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_ilevs_p2pmusic_engine_SoulseekNative_isOnline<'a>(
    _env: JNIEnv<'a>,
    _this: JObject<'a>,
) -> bool {
    guard(false, || {
        CLIENT
            .read()
            .ok()
            .and_then(|c| c.as_ref().map(|c| c.session_loss().is_none()))
            .unwrap_or(false)
    })
}

/// Searches for `timeout_ms`. One line per file: user, free slots, speed, size, path, tab-separated.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_ilevs_p2pmusic_engine_SoulseekNative_search<'a>(
    mut env: JNIEnv<'a>,
    _this: JObject<'a>,
    query: JString<'a>,
    timeout_ms: jlong,
) -> jstring {
    let query = string(&mut env, &query);
    let lines = guard(String::new(), || {
        let Ok(slot) = CLIENT.read() else {
            return String::new();
        };
        let Some(client) = slot.as_ref() else {
            return String::new();
        };
        let timeout = Duration::from_millis(u64::try_from(timeout_ms).unwrap_or(0));
        let mut out = String::new();
        for result in client.search(&query, timeout).unwrap_or_default() {
            for file in &result.files {
                // A path with a tab or newline would break the format; such files are rare, skip them.
                if file.name.contains(['\t', '\n']) || result.username.contains(['\t', '\n']) {
                    continue;
                }
                let _ = writeln!(
                    out,
                    "{}\t{}\t{}\t{}\t{}",
                    result.username, result.slots, result.speed, file.size, file.name
                );
            }
        }
        out
    });
    java(&mut env, &lines)
}

/// Queues `filename` from `username` into `dir` (as `<basename>.part`, renamed when done). Returns its id, or -2.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_ilevs_p2pmusic_engine_SoulseekNative_download<'a>(
    mut env: JNIEnv<'a>,
    _this: JObject<'a>,
    username: JString<'a>,
    filename: JString<'a>,
    size: jlong,
    dir: JString<'a>,
) -> jlong {
    let username = string(&mut env, &username);
    let filename = string(&mut env, &filename);
    let dir = string(&mut env, &dir);
    guard(FAILED, || {
        let Ok(slot) = CLIENT.read() else {
            return FAILED;
        };
        let Some(client) = slot.as_ref() else {
            return FAILED;
        };
        let size = u64::try_from(size).unwrap_or(0);
        let Ok((_, updates)) = client.download(filename.clone(), username.clone(), size, dir)
        else {
            return FAILED;
        };
        let id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
        let state = Arc::new(AtomicI64::new(QUEUED));
        let watched = Arc::clone(&state);
        std::thread::spawn(move || {
            for update in updates {
                let value = match update {
                    DownloadStatus::Queued => QUEUED,
                    DownloadStatus::InProgress {
                        bytes_downloaded, ..
                    }
                    | DownloadStatus::Paused {
                        bytes_downloaded, ..
                    } => i64::try_from(bytes_downloaded).unwrap_or(i64::MAX),
                    DownloadStatus::Completed => i64::try_from(size).unwrap_or(i64::MAX),
                    _ => FAILED,
                };
                watched.store(value, Ordering::Relaxed);
                if update.is_terminal() {
                    return;
                }
            }
            // The library dropped the channel without a final word: the transfer is gone.
            let _ = watched.compare_exchange(QUEUED, FAILED, Ordering::Relaxed, Ordering::Relaxed);
        });
        let mut downloads = DOWNLOADS
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        downloads.get_or_insert_with(HashMap::new).insert(
            id,
            Download {
                username,
                filename,
                state,
            },
        );
        id
    })
}

/// Bytes of download `id` on disk so far (its size when done), -1 while queued, -2 when it failed.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_ilevs_p2pmusic_engine_SoulseekNative_status<'a>(
    _env: JNIEnv<'a>,
    _this: JObject<'a>,
    id: jlong,
) -> jlong {
    guard(FAILED, || {
        DOWNLOADS
            .lock()
            .ok()
            .and_then(|d| {
                d.as_ref()?
                    .get(&id)
                    .map(|d| d.state.load(Ordering::Relaxed))
            })
            .unwrap_or(FAILED)
    })
}

/// Stops download `id`, e.g. the losers of a race for the same track.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_ilevs_p2pmusic_engine_SoulseekNative_cancel<'a>(
    _env: JNIEnv<'a>,
    _this: JObject<'a>,
    id: jlong,
) {
    guard((), || {
        let removed = DOWNLOADS
            .lock()
            .ok()
            .and_then(|mut d| d.as_mut()?.remove(&id));
        if let (Some(download), Ok(slot)) = (removed, CLIENT.read())
            && let Some(client) = slot.as_ref()
        {
            let _ = client.cancel_download(&download.username, &download.filename);
        }
    });
}
