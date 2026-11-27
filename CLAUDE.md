@AGENTS.md

# Project rules

- Talk to the owner in Russian. Code, comments, commits and PR titles stay in English.
- Architecture: one `:app` module split into packages (`engine`, `playback`, `search`, `catalog`, `library`, `ui`), manual wiring in `AppGraph`, no DI framework. libtorrent4j downloads, Media3 plays, `TorrentDataSource` connects them.
- The app runs standalone on a phone or a car head unit: no servers, no Google Play Services. minSdk 26 (Android 8.1 head units), native libs for arm64-v8a, armeabi-v7a, x86 and x86_64.
- Linters fail the build: ktlint, detekt with all rules, Android Lint with warnings as errors, Kotlin warnings as errors. Fix the code, do not suppress, unless a rule is clearly wrong for one line, and then say why next to the suppression.
- Every merged PR to `main` publishes a release APK through `.github/workflows/release.yml`.

# Metrics

Development is driven by numbers, not by impressions.

- The metrics are the user's path: `.torrent` download time, magnet metadata time (first launch and with the saved DHT), album tap to sound, and whether the path passes at all on every emulator level.
- CI measures them on every PR. A test or script prints `METRIC name=value`, and the job writes these lines to its summary. A new metric is one more such line, not a new tool.
- A change made for speed or reliability names its metric and reports the number before and after, in the PR and to the owner. No number, no claim: say "not measured" instead.
- A new feature on the user's path adds its own metric in the same PR.
- A metric that got worse blocks the merge until it is explained or fixed. Live numbers are noisy: compare several runs before calling a small difference a result.
