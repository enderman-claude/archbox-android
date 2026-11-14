plugins { id("com.android.application") }

android {
    namespace = "com.archbox.container"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.archbox.container"
        minSdk = 26
        // targetSdk 28 build (t28) on purpose: from 29 on, SELinux forbids executing files
        // from the app data dir, which is where the Arch rootfs lives.
        val tsdk = (findProperty("tsdk") as String?)?.toInt() ?: 34
        targetSdk = tsdk
        resValue("string", "app_label", "Archbox SDK$tsdk")
        // compiled in: shown in the first-boot banner and the launcher's startup line
        val sha = (findProperty("gitsha") as String?) ?: "dev"
        buildConfigField("String", "GIT_SHA", "\"${sha.take(7)}\"")
        buildConfigField("int", "TARGET_SDK", "$tsdk")
        // main build (-Pmain=true) keeps the plain package name; every other SDK build gets .t<sdk>
        val isMain = (findProperty("main") as String?) == "true"
        if (!isMain) applicationIdSuffix = ".t$tsdk"
        versionCode = (findProperty("vcode") as String?)?.toInt() ?: 1
        versionName = (findProperty("vname") as String?) ?: "0.1"
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        // The launcher is shipped as libarchbox.so and must exist as a real file.
        jniLibs { useLegacyPackaging = true }
    }
    // Release identity lives outside the repo: the keystore comes from CI secrets (see
    // .github/workflows/build.yml). Without the env vars, release falls back to the debug key.
    signingConfigs {
        create("release") {
            System.getenv("ARCHBOX_KEYSTORE_PATH")?.let { storeFile = file(it) }
            storePassword = System.getenv("ARCHBOX_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("ARCHBOX_KEY_ALIAS")
            keyPassword = System.getenv("ARCHBOX_KEY_PASSWORD")
            storeType = "pkcs12"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (System.getenv("ARCHBOX_KEYSTORE_PATH") != null) signingConfig = signingConfigs.getByName("release")
        }
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
