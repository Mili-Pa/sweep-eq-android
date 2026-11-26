plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val versionText = rootProject.file("MRCAL_VERSION").readText().trim()
val versionParts = versionText.split(".").map { it.toInt() }
val computedVersionCode = versionParts[0] * 10000 + versionParts[1] * 100 + versionParts[2]

android {
    namespace = "com.milipa.mr.callibrator"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.milipa.mr.callibrator"
        minSdk = 31
        targetSdk = 34
        versionCode = computedVersionCode
        versionName = versionText
    }

    // Same signing key/secrets as Sweep EQ (never stored in the repo).
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
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
}
