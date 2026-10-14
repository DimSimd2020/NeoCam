plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.poco.camera"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.poco.camera"
        minSdk = 29
        targetSdk = 35
        versionCode = 4
        versionName = "2.2"
        val defProfile = (project.findProperty("defaultProfile") as? String) ?: "PIXEL_8"
        buildConfigField("String", "DEFAULT_PROFILE", "\"$defProfile\"")
        buildConfigField("String", "AUTHOR", "\"DimSimd\"")
        resValue("string", "app_name", "NeoCam")
    }

    signingConfigs {
        val persistentKey = System.getenv("NEOCAM_KEYSTORE_PATH")
        if (!persistentKey.isNullOrBlank()) {
            create("persistentRelease") {
                storeFile = file(persistentKey)
                storePassword = System.getenv("NEOCAM_STORE_PASSWORD")
                keyAlias = System.getenv("NEOCAM_KEY_ALIAS")
                keyPassword = System.getenv("NEOCAM_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // A persistent production key is required for in-place Android updates.
            signingConfig = if (!System.getenv("NEOCAM_KEYSTORE_PATH").isNullOrBlank()) {
                signingConfigs.getByName("persistentRelease")
            } else {
                signingConfigs.getByName("debug")
            }
        }
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
        buildConfig = true
    }
}

dependencies {
    val camerax = "1.4.2"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("androidx.camera:camera-extensions:$camerax")

    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    implementation("com.google.mlkit:object-detection:17.0.2")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-video:2.7.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
}
