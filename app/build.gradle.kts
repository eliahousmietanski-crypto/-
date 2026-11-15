plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aiwa.asb060"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.aiwa.asb060"
        minSdk = 23
        targetSdk = 34
        versionCode = 4
        versionName = "2.0"
    }

    signingConfigs {
        // Clé de signature fixe, rangée dans le dépôt : chaque nouvelle version s'installe ainsi
        // par-dessus la précédente (sans désinstaller, donc sans perdre les codes enregistrés),
        // quel que soit l'ordinateur qui compile.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { it.testLogging { events("failed"); showStandardStreams = false } }
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
