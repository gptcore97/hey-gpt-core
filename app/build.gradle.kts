plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Schema di ritorno del login Google, ricavato da assets/www/config.json (android_client_id oppure ios_client_id)
val googleScheme: String = run {
    val f = file("src/main/assets/www/config.json")
    val txt = if (f.exists()) f.readText() else ""
    fun pick(k: String) = Regex("\"" + k + "\"\\s*:\\s*\"([^\"]+)\"").find(txt)?.groupValues?.get(1)
    val id = pick("android_client_id") ?: pick("ios_client_id") ?: ""
    if (id.isBlank()) "gptcore.noclient" else "com.googleusercontent.apps." + id.removeSuffix(".apps.googleusercontent.com")
}

android {
    namespace = "it.flubatir.coreassistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "it.flubatir.coreassistant"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "2.0"
        manifestPlaceholders["googleScheme"] = googleScheme
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
