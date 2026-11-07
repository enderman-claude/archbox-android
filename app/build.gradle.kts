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
        if (tsdk != 28) applicationIdSuffix = ".t$tsdk"
        versionCode = 1
        versionName = "0.1"
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
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    implementation("com.github.termux.termux-app:terminal-view:v0.118.0")
    implementation("com.github.termux.termux-app:terminal-emulator:v0.118.0")
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation("androidx.annotation:annotation:1.8.0")
}
