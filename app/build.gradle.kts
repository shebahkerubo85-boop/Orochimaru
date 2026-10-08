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

        buildConfigField("String", "SIMKL_CLIENT_ID", "\"\"")
        buildConfigField("String", "SIMKL_CLIENT_SECRET", "\"\"")
        buildConfigField("long", "BUILD_DATE", "System.currentTimeMillis()")
        buildConfigField("String", "MAL_KEY", "\"\"")
    }

    flavorDimensions += listOf("store", "device")

    productFlavors {
        create("fdroid") {
            dimension = "store"
            versionNameSuffix = "-fdroid"
        }
        create("google") {
            dimension = "store"
            isDefault = true
        }

        // Phone builds bundle ffmpeg (via ffmpeg-kit) for HLS downloads, which requires API 24.
        create("phone") {
            dimension = "device"
            isDefault = true
            minSdk = 24
        }
        // TV builds skip ffmpeg entirely, so they keep the original API 23 floor.
        create("tv") {
            dimension = "device"
            minSdk = 23
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

    packaging {
        jniLibs {
            // The phone build no longer pulls nextlib's FFmpeg copies (see dependencies), so the
            // FFmpeg libs here come solely from ffmpeg-kit; the picks are kept as a safety net in
            // case another dependency starts shipping them. libc++_shared is shared across several
            // native deps and still needs picking.
            pickFirsts += listOf(
                "lib/**/libavcodec.so",
                "lib/**/libavutil.so",
                "lib/**/libswresample.so",
                "lib/**/libswscale.so",
                "lib/**/libc++_shared.so",
            )
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
    implementation(libs.previewseekbar.media3)
    implementation(libs.bundles.subtitles)

    // UI
    implementation(libs.material)
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
    implementation(libs.lottie)
    implementation(libs.shimmer)
    implementation(files("libs/AnimatedBottomBar-7fcb9af.aar"))
    implementation(libs.qrcode.kotlin)
    implementation(libs.gesture)

    implementation(libs.bundles.markwon)
    implementation(libs.bundles.rx)
    implementation(libs.bundles.groupie)
    implementation(libs.charts)
    implementation(libs.dialogs)
    implementation(libs.fuzzy)
    implementation(libs.overlappingpanels)
    implementation(libs.paging)
    implementation(libs.bundles.okhttp)
    implementation(libs.okio)
    // ffmpeg-kit is only bundled in phone builds; the tv build has no ffmpeg at all.
    add("phoneImplementation", libs.ffmpeg.kit.get())
    add("phoneImplementation", libs.smart.exception.java.get())


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

    // === CS3 Player upstream deps ===
    implementation(libs.coil3)
    implementation(libs.coil.network.okhttp)
    implementation(libs.media3.common)
    implementation(libs.media3.container)
    implementation(libs.lifecycle.livedata.ktx)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.navigation.fragment.ktx)
    implementation(libs.navigation.ui.ktx)
    implementation(libs.androidx.biometric)
    implementation(libs.colorpicker)
    implementation(libs.constraintlayout)
    implementation(libs.core.ktx)
    implementation(libs.work.runtime.ktx)
    // Upstream CS3 dependencies
    implementation(libs.safefile)
    implementation(libs.nicehttp)
    implementation(libs.jsoup)
    implementation(libs.juniversalchardet)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.palette.ktx)
    implementation(libs.preference.ktx)
    implementation(libs.activity.ktx)
    implementation(libs.annotation)
    implementation(libs.appcompat)
    implementation(libs.fragment.ktx)
    implementation(libs.json)
    implementation(libs.tvprovider)
    // nextlib-media3ext bundles its own FFmpeg 6.0 (libavcodec/libavutil/libswresample/libswscale),
    // which collides with ffmpeg-kit's copies in the phone build. The duplicate libavutil has no
    // av_set_saf_open symbol, so whichever copy won the jni merge broke ffmpeg-kit at load time.
    // Phone uses a repackaged nextlib (libs/, same classes, FFmpeg libs removed) so ffmpeg-kit's
    // copies win and nextlib's libmedia3ext.so links against them. tv keeps the stock artifact.
    add("tvImplementation", libs.nextlib.media3ext.get())
    add("phoneImplementation", files("libs/nextlib-media3ext-1.9.3-0.12.0-noffmpeg.aar"))
    // nextlib's JNI (libmedia3ext.so) links against media3-exoplayer, which the bundle already
    // provides; error_prone_annotations was only declared by the nextlib POM.
    add("phoneImplementation", "com.google.errorprone:error_prone_annotations:2.48.0")
    coreLibraryDesugaring(libs.desugar.jdk.libs.nio)
}
