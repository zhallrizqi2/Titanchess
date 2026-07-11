plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.chesscapture"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.chesscapture"
        minSdk = 29
        targetSdk = 36

        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            abiFilters += listOf(
                "arm64-v8a",
                "armeabi-v7a"
            )
        }
    }

    buildTypes {

        release {
            isMinifyEnabled = false

            proguardFiles(
                getDefaultProguardFile(
                    "proguard-android-optimize.txt"
                ),
                "proguard-rules.pro"
            )
        }

        debug {
            isDebuggable = true
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
    }

    composeOptions {

        kotlinCompilerExtensionVersion = "1.5.15"

    }

    packaging {

        resources {

            excludes += "/META-INF/{AL2.0,LGPL2.1}"

        }

    }

}

dependencies {

    // ===========================
    // Android Core
    // ===========================

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")

    // ===========================
    // Activity Compose
    // ===========================

    implementation("androidx.activity:activity-compose:1.10.1")

    // ===========================
    // Jetpack Compose BOM
    // ===========================

    implementation(platform("androidx.compose:compose-bom:2025.06.00"))

    implementation("androidx.compose.ui:ui")

    implementation("androidx.compose.ui:ui-tooling-preview")

    implementation("androidx.compose.material3:material3")

    implementation("androidx.compose.foundation:foundation")

    implementation("androidx.compose.runtime:runtime")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // ===========================
    // Lifecycle
    // ===========================

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")

    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")

    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")

    // ===========================
    // Coroutines
    // ===========================

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // ===========================
    // Camera / Image
    // ===========================

    implementation("androidx.exifinterface:exifinterface:1.4.1")

    // ===========================
    // TensorFlow Lite
    // (digunakan nanti)
    // ===========================

    implementation("org.tensorflow:tensorflow-lite:2.17.0")

    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")

    // ===========================
    // OpenCV
    // SDK akan ditambahkan nanti
    // ===========================

    implementation(fileTree("libs") {
        include("*.jar", "*.aar")
    })

    // ===========================
    // Testing
    // ===========================

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")

    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")

}
