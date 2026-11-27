package io.github.ilevs.p2pmusic.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

private const val MEMORY_COVERS = 100

private val coverClient = OkHttpClient()

// Decoded covers, so a row scrolled back shows its art at once.
private val memory = LruCache<String, ImageBitmap>(MEMORY_COVERS)

/** Album art from [url]; a blank square while it loads or when there is none. */
@Composable
fun Cover(
    url: String,
    modifier: Modifier = Modifier,
) {
    val dir = File(LocalContext.current.cacheDir, "covers")
    val image by produceState(memory[url], url) {
        value = value ?: withContext(Dispatchers.IO) { runCatching { loadCover(url, dir) }.getOrNull() }
        value?.let { memory.put(url, it) }
    }
    Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        image?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

// Covers stay on disk, in the cache the system may clear, so they also show without a network.
private fun loadCover(
    url: String,
    dir: File,
): ImageBitmap? {
    val file = File(dir, url.replace(Regex("\\W"), "_"))
    if (!file.exists()) {
        coverClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
            val bytes = response.body?.takeIf { response.isSuccessful }?.bytes() ?: return null
            dir.mkdirs()
            // Written aside and renamed, so a download cut short never leaves half a cover.
            val part = File(dir, "${file.name}.part")
            part.writeBytes(bytes)
            part.renameTo(file)
        }
    }
    return BitmapFactory.decodeFile(file.path)?.asImageBitmap()
}
