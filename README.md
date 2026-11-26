# P2P Music

Android-приложение для стриминга музыки с опытом как у Spotify, где треки
приходят из торрентов.

## Стек

- Kotlin 2.0, Jetpack Compose, Material 3
- Gradle Kotlin DSL + version catalog (`gradle/libs.versions.toml`)
- minSdk 26, targetSdk 36, JDK 17

## Проверки

Все проверки валят сборку при любом замечании.

| Инструмент | Настройка |
|---|---|
| ktlint 1.5 | стиль `ktlint_official`, включены experimental-правила (`.editorconfig`) |
| detekt 1.23 | `allRules = true`, `maxIssues: 0`, плюс правила для Compose (`config/detekt/detekt.yml`) |
| Android Lint | `checkAllWarnings`, `warningsAsErrors`, проверка зависимостей и тестов |
| Kotlin compiler | `allWarningsAsErrors`, `progressiveMode` |

Локально:

```bash
./gradlew ktlintCheck detekt lintDebug testDebugUnitTest assembleDebug
./gradlew ktlintFormat   # автоисправление стиля
```

## CI/CD

- `.github/workflows/ci.yml` запускает все проверки на каждый pull request.
- `.github/workflows/release.yml` на каждый push в `main` прогоняет проверки,
  собирает release APK и публикует GitHub Release `v0.1.<номер запуска>`.

### Подпись релиза

Без секретов release APK подписывается debug-ключом (ставится, но не
годится для публикации). Для настоящей подписи добавьте в
*Settings → Secrets and variables → Actions*:

| Секрет | Значение |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | пароль keystore |
| `ANDROID_KEY_ALIAS` | alias ключа |
| `ANDROID_KEY_PASSWORD` | пароль ключа |

Создать keystore:

```bash
keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 4096 \
  -validity 10000 -alias p2p-music
```

Важно: после перехода с debug-ключа на release-ключ уже установленное
приложение придётся удалить перед установкой новой версии.
