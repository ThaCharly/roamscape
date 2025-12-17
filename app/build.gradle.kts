plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.charly.wallpapermap"
    compileSdk = 33

    defaultConfig {
        applicationId = "com.charly.wallpapermap"
        minSdk = 29         // *Tal como pediste: API 29 como mínimo*
        targetSdk = 33
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isDebuggable = true

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
        viewBinding = true
        dataBinding = true
    }
}

dependencies {
    // Core
    implementation("androidx.core:core-ktx:1.9.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.6.1")

    // UI / preferences (simple)
    implementation("androidx.preference:preference-ktx:1.2.0")
    implementation("com.google.android.material:material:1.7.0") // Material básico

    // OSM
    implementation("org.osmdroid:osmdroid-android:6.1.16")
    implementation("com.google.android.gms:play-services-location:21.0.1")





    // Kotlin stdlib (automáticas con plugin pero dejo explícito)
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.0")
}
