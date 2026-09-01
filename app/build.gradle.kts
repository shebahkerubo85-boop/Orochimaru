plugins {
    alias(libs.plugins.android)
    alias(libs.plugins.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose)
}

if (gradle.startParameter.taskNames.any { it.contains("google", true) }) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
}

val gitCommitHash = providers.exec {
    commandLine("git", "rev-parse", "--verify", "--short", "HEAD")
}.standardOutput.asText.get().trim()

android {
    namespace = "ani.sanin"
    compileSdk = 36

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("release.keystore")
            storePassword = "sanin123"
            keyAlias = "sanin"
            keyPassword = "sanin123"
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = false
        }
    }

    defaultConfig {
        applicationId = "ani.sanin"
        minSdk = 23
        targetSdk = 36

        versionName = "3.2.2"
        versionCode = (versionName ?: "1.0.0").split(".")
            //noinspection WrongGradleMethod
            .map { it.toInt() * 100 }
            .joinToString("")
            .toInt()

        signingConfig = signingConfigs.getByName("debug")
    }

    flavorDimensions += "store"

    productFlavors {
        create("fdroid") {
            dimension = "store"
            versionNameSuffix = "-fdroid"
        }
        create("google") {
            dimension = "store"
            isDefault = true
        }
    }

    buildTypes {
        create("alpha") {
            applicationIdSuffix = ".beta"
            versionNameSuffix = "-alpha01-$gitCommitHash"
            isDebuggable = true
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            isDefault = true
        }

        getByName("debug") {
            applicationIdSuffix = ".beta"
            versionNameSuffix = "-beta01"
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            // mpv-android-lib bundles its own FFmpeg; nextlib-media3ext also ships FFmpeg.
            // pickFirsts ensures no merge error; source set jniLibs (mpv's FFmpeg extracted
            // below) are processed BEFORE AAR copies, so mpv's versions always win.
            pickFirsts += setOf(
                "**/libavcodec.so",
                "**/libavformat.so",
                "**/libavutil.so",
                "**/libswresample.so",
                "**/libswscale.so",
                "**/libc++_shared.so",
            )
        }
    }
}

// Extract mpv's FFmpeg .so files from its AAR into a source-set directory.
// Source set jniLibs are processed BEFORE AAR extractions during the merge,
// so these files always win the pickFirsts race against nextlib-media3ext's copies.
val extractMpvFfmpeg by tasks.registering(Copy::class) {
    description = "Extracts mpv FFmpeg .so files from mpv-android-lib AAR"
    group = "build"
    doFirst {
        // Resolve mpv AAR directly (no transitive deps) via a detached configuration
        val mpvConfig = project.configurations.detachedConfiguration(
            project.dependencies.create("io.github.abdallahmehiz:mpv-android-lib:0.1.9")
        )
        mpvConfig.isTransitive = false
        val mpvAar = mpvConfig.singleFile
        project.logger.lifecycle("Extracting mpv FFmpeg from: ${mpvAar.name}")
        from(zipTree(mpvAar)) {
            include("jni/*/libav*.so")
            include("jni/*/libsw*.so")
        }
        into(layout.buildDirectory.dir("mpv-ffmpeg-libs"))
    }
}

android {
    sourceSets {
        getByName("main") {
            jniLibs.srcDir(layout.buildDirectory.dir("mpv-ffmpeg-libs"))
        }
    }
}

tasks.configureEach {
    if (name.contains("merge", ignoreCase = true) && name.contains("NativeLib", ignoreCase = true)) {
        dependsOn(extractMpvFfmpeg)
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        freeCompilerArgs.addAll(
            "-XXLanguage:+ContextParameters",
            "-Xmulti-platform",
            "-opt-in=com.lagradost.cloudstream3.InternalAPI",
            "-opt-in=com.lagradost.cloudstream3.Prerelease",
            "-opt-in=kotlin.uuid.ExperimentalUuidApi"
        )
    }
}

dependencies {

    // Firebase
    add("googleImplementation", platform(libs.firebase.bom))
    add("googleImplementation", libs.bundles.firebase)

    // AndroidX
    implementation(libs.bundles.androidx)

    // Kotlin
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlin.stdlib)

    // Core libs
    implementation(libs.bundles.misc)

    // Glide
    implementation(libs.bundles.glide)
    ksp(libs.glide.ksp)

    implementation(libs.bundles.media3)
    implementation(libs.bundles.subtitles)
    implementation(libs.mediarouter)

    // mpv (libmpv) player engine — port of Zangetsu live-stream handling
    implementation("io.github.abdallahmehiz:mpv-android-lib:0.1.9")

    // UI
    implementation(libs.material)
    implementation(files("libs/AnimatedBottomBar-7fcb9af.aar"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
    implementation(libs.compose.runtime)
    implementation(libs.compose.activity)
    implementation(libs.flexbox)
    implementation(libs.kenburns)
    implementation(libs.subsampling)
    implementation(libs.gesture)
    implementation(libs.ebook)
    implementation(libs.dialogs)
    implementation(libs.charts)
    implementation(libs.lottie)
    implementation(libs.qrcode.kotlin)

    implementation(libs.bundles.markwon)
    implementation(libs.bundles.groupie)
    implementation(libs.bundles.rx)
    implementation(libs.bundles.okhttp)
    implementation(libs.okio)


    // CloudStream .cs3 plugin runtime (vendored com.lagradost.cloudstream3 library)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlinx.atomicfu)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.io.core)
    implementation(libs.ksoup)
    implementation(libs.ktor.http)
    implementation(libs.cryptography.core)
    implementation(libs.cryptography.provider.optimal)
    implementation(libs.newpipeextractor)
    implementation(libs.rhino)
    implementation(libs.androidsvg.aar)

    // Archive support (local source)
    implementation(libs.libarchive)
    implementation(libs.xmlutil.core)
    implementation(libs.xmlutil.serialization)
}