plugins { id("com.android.application") }

android {
    namespace = "dev.archbox"
    compileSdk = 34
    defaultConfig {
        applicationId = "dev.archbox"
        minSdk = 26
        // targetSdk 28 on purpose: from 29 on, SELinux forbids executing files
        // from the app data dir, which is where the Arch rootfs lives.
        val tsdk = (findProperty("tsdk") as String?)?.toInt() ?: 28
        targetSdk = tsdk
        resValue("string", "app_label", "Archbox SDK$tsdk")
        // compiled in: shown in the first-boot banner and the launcher's startup line
        val sha = (findProperty("gitsha") as String?) ?: "dev"
        buildConfigField("String", "GIT_SHA", "\"${sha.take(7)}\"")
        buildConfigField("int", "TARGET_SDK", "$tsdk")
        // beta-only features (e.g. the debug crash menu item): off unless built with -PbetaFeatures=true
        val beta = (findProperty("betaFeatures") as String?)?.toBoolean() ?: false
        buildConfigField("boolean", "BETA_FEATURES", "$beta")
        if (tsdk != 28) applicationIdSuffix = ".t$tsdk"
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += "arm64-v8a" }
    }
    // Same signing identity as enderman-claude/camera-app, so Android sees one developer.
    // CI sets CAMERA_KEYSTORE_PATH; without it, builds keep the default debug key.
    signingConfigs {
        create("release") {
            val ks = System.getenv("CAMERA_KEYSTORE_PATH")
            if (ks != null) {
                storeFile = file(ks)
                storePassword = System.getenv("CAMERA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CAMERA_KEY_ALIAS")
                keyPassword = System.getenv("CAMERA_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("debug") {
            if (System.getenv("CAMERA_KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        // The launcher is shipped as libarchbox.so and must exist as a real file.
        jniLibs { useLegacyPackaging = true }
    }
    buildFeatures { buildConfig = true }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    implementation("com.github.termux.termux-app:terminal-view:v0.118.0")
    implementation("com.github.termux.termux-app:terminal-emulator:v0.118.0")
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation("org.tukaani:xz:1.9")   // .tar.xz support in commons-compress
    implementation("androidx.annotation:annotation:1.8.0")
}
