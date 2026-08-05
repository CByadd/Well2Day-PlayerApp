import java.util.Properties
import java.io.FileInputStream
import java.io.FileOutputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// 1. Locate the file in the ROOT directory
val versionPropsFile = rootProject.file("version.properties")

// 2. Load the properties
val vProps = Properties().apply {
    if (versionPropsFile.exists()) {
        versionPropsFile.inputStream().use { load(it) }
    }
}

// 3. Extract values (Fallback to 0 to easily detect if file reading fails)
val majorValue = vProps.getProperty("MAJOR", "1").toInt()
val minorValue = vProps.getProperty("MINOR", "75").toInt()
val vCodeValue = vProps.getProperty("VERSION_CODE", "1").toInt()
val vNameValue = "$majorValue.${String.format("%02d", minorValue)}"

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
        versionCode = vCodeValue
        versionName = vNameValue

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "flow"
    productFlavors {
        create("normal") {
            dimension = "flow"
            applicationId = "com.example.playerapp.f3.normal"
            versionNameSuffix = "-Normal"
            buildConfigField("String", "FLOW_TYPE", "\"\"")
            resValue("string", "app_name", "Well2Day BMI")
        }
        create("f1") {
            dimension = "flow"
            applicationId = "com.example.playerapp.f3.f1"
            versionNameSuffix = "-F1"
            buildConfigField("String", "FLOW_TYPE", "\"F1\"")
            resValue("string", "app_name", "Well2Day BMI")
        }
        create("f2") {
            dimension = "flow"
            applicationId = "com.example.playerapp.f3.f2"
            versionNameSuffix = "-F2"
            buildConfigField("String", "FLOW_TYPE", "\"F2\"")
            resValue("string", "app_name", "Well2Day BMI")
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
    
    buildFeatures {
        compose = true
        buildConfig = true
    }

    applicationVariants.all {
        outputs.all {
            val outputImpl = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            // We use vNameValue directly here to avoid flavor suffixes in the filename
            outputImpl.outputFileName = "Well2day BMI App v$vNameValue.apk"
        }
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
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.socket:socket.io-client:2.1.0")
    implementation("com.github.bumptech.glide:glide:4.16.0")
    implementation("com.github.bumptech.glide:compose:1.0.0-beta01")
    implementation("com.github.bumptech.glide:okhttp3-integration:4.16.0")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("io.coil-kt:coil-gif:2.6.0")
    implementation("com.google.zxing:core:3.5.1")
    implementation("com.airbnb.android:lottie-compose:6.1.0")
    implementation("androidx.compose.material:material-icons-extended:1.5.4")
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-ui:1.2.1")
    implementation("androidx.media3:media3-common:1.2.1")
    implementation("androidx.media3:media3-datasource:1.2.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.2.1")
    implementation("androidx.media3:media3-database:1.2.1")
    implementation("androidx.activity:activity-compose:1.8.0")
    implementation("androidx.webkit:webkit:1.8.0")
    implementation("com.github.mik3y:usb-serial-for-android:3.7.0")
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

// This task handles the actual increment logic
tasks.register("incrementVersion") {
    doLast {
        val props = Properties()
        if (versionPropsFile.exists()) {
            versionPropsFile.inputStream().use { props.load(it) }
        }

        var major = props.getProperty("MAJOR", "1").toInt()
        var minor = props.getProperty("MINOR", "75").toInt()
        var vCode = props.getProperty("VERSION_CODE", "1").toInt()

        vCode++
        minor++
        if (minor > 99) {
            minor = 0
            major++
        }

        props.setProperty("MAJOR", major.toString())
        props.setProperty("MINOR", minor.toString())
        props.setProperty("VERSION_CODE", vCode.toString())

        versionPropsFile.outputStream().use {
            props.store(it, "Auto-incremented version properties")
        }
        println(">>> Version successfully incremented to $major.${String.format("%02d", minor)} for the NEXT build.")
    }
}

// Hook the increment task to run after ANY assemble or bundle task
tasks.configureEach {
    if (name.startsWith("assemble") || name.startsWith("bundle")) {
        finalizedBy("incrementVersion")
    }
}
