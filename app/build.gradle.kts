plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

import org.gradle.jvm.toolchain.JavaLanguageVersion

android {
    namespace = "com.example.playerapp"
    compileSdk = 36
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    kotlinOptions {
        jvmTarget = "17"
    }

    defaultConfig {
        minSdk = 21
        targetSdk = 36
        versionCode = 1
        versionName = "F3-1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Product flavors for different flow types
    flavorDimensions += "flow"
    productFlavors {
        create("normal") {
            dimension = "flow"
            applicationId = "com.example.playerapp.f3.normal"
            versionNameSuffix = "-Normal"
            buildConfigField("String", "FLOW_TYPE", "\"\"")
            resValue("string", "app_name", "F3 Normal")
        }
        create("f1") {
            dimension = "flow"
            applicationId = "com.example.playerapp.f3.f1"
            versionNameSuffix = "-F1"
            buildConfigField("String", "FLOW_TYPE", "\"F1\"")
            resValue("string", "app_name", "Adscape BMI")
        }
        create("f2") {
            dimension = "flow"
            applicationId = "com.example.playerapp.f3.f2"
            versionNameSuffix = "-F2"
            buildConfigField("String", "FLOW_TYPE", "\"F2\"")
            resValue("string", "app_name", "F3 F2")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation("com.google.android.material:material:1.10.0")
    // Network
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Socket.IO for real-time updates
    implementation("io.socket:socket.io-client:2.1.0")
    
    // Image loading (kiosk-grade caching + preloading)
    implementation("com.github.bumptech.glide:glide:4.16.0")
    implementation("com.github.bumptech.glide:compose:1.0.0-beta01")
    // Optional: OkHttp integration for Glide (better networking behavior)
    implementation("com.github.bumptech.glide:okhttp3-integration:4.16.0")

    // Coil is still used in a few legacy UI surfaces; will be migrated incrementally.
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("io.coil-kt:coil-gif:2.6.0")
    // QR code generation (ZXing core)
    implementation("com.google.zxing:core:3.5.1")
    // Lottie animations
    implementation("com.airbnb.android:lottie-compose:6.1.0")
    // Material Icons Extended
    implementation("androidx.compose.material:material-icons-extended:1.5.4")
    
    // Media3 (ExoPlayer) for kiosk-grade playback + caching
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-ui:1.2.1")
    implementation("androidx.media3:media3-common:1.2.1")
    implementation("androidx.media3:media3-datasource:1.2.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.2.1")
    implementation("androidx.media3:media3-database:1.2.1")
    implementation("androidx.activity:activity-compose:1.8.0")
    implementation("androidx.webkit:webkit:1.8.0")
    // USB Serial for Android
    implementation("com.github.mik3y:usb-serial-for-android:3.7.0")

    // Room Database
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // WorkManager for background sync
    implementation(libs.androidx.work.runtime.ktx)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}




