@AGENTS.md

# Project rules

- Talk to the owner in Russian. Code, comments, commits and PR titles stay in English.
- Architecture: one `:app` module split into packages (`engine`, `playback`, `search`, `catalog`, `library`, `ui`), manual wiring in `AppGraph`, no DI framework. libtorrent4j downloads, Media3 plays, `TorrentDataSource` connects them.
- The app runs standalone on a phone or a car head unit: no servers, no Google Play Services. minSdk 26 (Android 8.1 head units), native libs for arm64-v8a, armeabi-v7a, x86 and x86_64.
- Linters fail the build: ktlint, detekt with all rules, Android Lint with warnings as errors, Kotlin warnings as errors. Fix the code, do not suppress, unless a rule is clearly wrong for one line, and then say why next to the suppression.
- Every merged PR to `main` publishes a release APK through `.github/workflows/release.yml`.
