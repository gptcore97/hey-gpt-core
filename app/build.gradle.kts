plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "it.flubatir.coreassistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "it.flubatir.coreassistant"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"

        // Schema di ritorno dell'accesso Google: ricavato da google.ios_client_id in src/main/assets/config.json
        val cfgText = file("src/main/assets/config.json").takeIf { it.exists() }?.readText() ?: ""
        val gid = Regex("\"ios_client_id\"\\s*:\\s*\"([^\"]*)\"").find(cfgText)?.groupValues?.get(1) ?: ""
        manifestPlaceholders["googleScheme"] =
            if (gid.isBlank()) "com.googleusercontent.apps.notconfigured"
            else "com.googleusercontent.apps." + gid.removeSuffix(".apps.googleusercontent.com")
    }

    // Keystore fisso nel repository: ogni build e' firmata uguale, cosi' puoi aggiornare l'app senza disinstallarla.
    signingConfigs {
        create("fixed") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
