plugins {
    id("com.android.application")
}

android {
    namespace = "com.nodevortex.aura"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nodevortex.aura"
        minSdk = 28
        // Deliberately 28: keeps the legacy Wi-Fi connect + Bluetooth APIs available so the
        // quick-settings panel can join networks and pair devices inline.
        targetSdk = 28
        versionCode = 3
        versionName = "1.2"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // R8-optimised, non-debuggable: ART can AOT-compile it (debug builds always run JIT-only)
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi")
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "kotlin/**", "DebugProbesKt.bin")
    }
}

dependencies {
}
