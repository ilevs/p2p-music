import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
}

// CI passes -PversionCode=<run number>; local builds fall back to 1.
val appVersionCode = providers.gradleProperty("versionCode").orNull?.toInt() ?: 1
val appVersionName = providers.gradleProperty("versionName").orNull ?: "0.1.0-local"

// Release signing comes from environment variables. When they are missing
// (local builds, forks, or before the keystore secret exists) the release
// build is signed with the debug key so CI still produces an installable APK.
val releaseKeystorePath: String? = System.getenv("ANDROID_KEYSTORE_PATH")
val hasReleaseKeystore = !releaseKeystorePath.isNullOrBlank() && file(releaseKeystorePath).exists()

// The Soulseek client is Rust (native/soulseek): cargo-ndk builds it for the four ABIs, cargo for unit tests.
val rustDir: File = rootProject.file("native/soulseek")
val rustSources = fileTree(rustDir) { exclude("target") }
val rustJniLibs = layout.buildDirectory.dir("rustJniLibs")
val cargoNdk =
    tasks.register<Exec>("cargoNdk") {
        workingDir = rustDir
        inputs.files(rustSources)
        outputs.dir(rustJniLibs)
        val abis = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64").flatMap { listOf("-t", it) }
        commandLine(
            listOf("cargo", "ndk", "--platform", "26") + abis +
                listOf("-o", rustJniLibs.get().asFile.path, "build", "--release"),
        )
    }
val cargoHost =
    tasks.register<Exec>("cargoHost") {
        workingDir = rustDir
        inputs.files(rustSources)
        outputs.file(File(rustDir, "target/release/libsoulseek.so"))
        commandLine("cargo", "build", "--release")
    }
tasks.named("preBuild") { dependsOn(cargoNdk) }

android {
    namespace = "io.github.ilevs.p2pmusic"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.ilevs.p2pmusic"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(requireNotNull(releaseKeystorePath))
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig =
                if (hasReleaseKeystore) {
                    signingConfigs.getByName("release")
                } else {
                    signingConfigs.getByName("debug")
                }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = false
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        checkAllWarnings = true
        checkDependencies = true
        checkReleaseBuilds = true
        checkTestSources = true
        explainIssues = true
        // These depend on what was published upstream today, not on our code,
        // so they would make CI fail randomly. Dependabot handles updates.
        disable +=
            setOf(
                "NewerVersionAvailable",
                "GradleDependency",
                "AndroidGradlePluginVersion",
            )
        textReport = true
        sarifReport = true
        htmlReport = true
    }

    // libtorrent is ~16 MB per ABI and stays uncompressed and page-aligned in the APK, the platform
    // default: v0.1.4 with compressed libs did not install on a Pixel 9 with Android 17 while v0.1.2
    // without them did. Per-ABI APKs keep the download small.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    sourceSets {
        getByName("main") { jniLibs.srcDir(rustJniLibs) }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
        progressiveMode.set(true)
    }
}

detekt {
    buildUponDefaultConfig = true
    allRules = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    source.setFrom("src/main/kotlin", "src/test/kotlin")
    parallel = true
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = "17"
    reports {
        html.required.set(true)
        sarif.required.set(true)
        xml.required.set(false)
        txt.required.set(false)
        md.required.set(false)
    }
}

// Unit tests stream real torrents between two libtorrent sessions on the CI machine (Linux x86_64),
// so they need libtorrent's desktop native library next to the Android ones.
val libtorrentDesktop: Configuration by configurations.creating
val libtorrentJniDir = layout.buildDirectory.dir("libtorrent-jni")
val extractLibtorrentJni =
    tasks.register<Copy>("extractLibtorrentJni") {
        from({ libtorrentDesktop.map { zipTree(it) } })
        include("lib/x86_64/libtorrent4j.so")
        eachFile { path = name }
        includeEmptyDirs = false
        into(libtorrentJniDir)
    }

tasks.withType<Test>().configureEach {
    dependsOn(extractLibtorrentJni, cargoHost)
    systemProperty("java.library.path", File(rustDir, "target/release").absolutePath)
    // A native crash (libtorrent, the Soulseek library) leaves its report where CI prints it.
    jvmArgs("-XX:ErrorFile=${layout.buildDirectory.get().asFile}/hs_err_pid%p.log")
    // LiveStreamTest prints swarm state and timings for the CI log.
    testLogging.showStandardStreams = true
    // The last test started names the culprit when the test JVM dies without a report.
    testLogging.events("started", "failed")
    // One JVM per test class: libtorrent sessions of one class do not outlive it into the next.
    forkEvery = 1
    systemProperty(
        "libtorrent4j.jni.path",
        libtorrentJniDir
            .get()
            .file("libtorrent4j.so")
            .asFile.absolutePath,
    )
}

ktlint {
    version.set(libs.versions.ktlint)
    android.set(true)
    ignoreFailures.set(false)
    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.SARIF)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.libtorrent4j)
    implementation(libs.okhttp)
    implementation(libs.jsoup)
    // Native libtorrent for phones (arm64), older head units (arm) and Intel head units like Haval Jolion (x86).
    implementation(libs.libtorrent4j.android.arm)
    implementation(libs.libtorrent4j.android.arm64)
    implementation(libs.libtorrent4j.android.x86)
    implementation(libs.libtorrent4j.android.x64)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    // org.json ships with Android; unit tests run on a plain JVM.
    testImplementation(libs.org.json)
    libtorrentDesktop(libs.libtorrent4j.linux)

    detektPlugins(libs.detekt.compose.rules)
}
