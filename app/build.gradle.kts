plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Version comes from the VERSION file (major.minor.patch); versionCode is derived from it.
val versionText = rootProject.file("VERSION").readText().trim()
val versionParts = versionText.split(".").map { it.toInt() }
val computedVersionCode = versionParts[0] * 10000 + versionParts[1] * 100 + versionParts[2]

android {
    namespace = "com.example.sweepeq"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.sweepeq"
        minSdk = 24
        targetSdk = 34
        versionCode = computedVersionCode
        versionName = versionText
    }

    // Signing key and passwords come from GitHub Actions secrets (never stored in the repo).
    signingConfigs {
        create("app") {
            storeFile = rootProject.file("signing/sweepeq.keystore")
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("app")
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
