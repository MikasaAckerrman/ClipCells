plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val ciStoreFile: String? = System.getenv("CLIPCELLS_STORE_FILE")
val ciStorePassword: String? = System.getenv("CLIPCELLS_STORE_PASSWORD")
val stableSigningAvailable: Boolean = ciStoreFile != null &&
    ciStorePassword != null &&
    file(ciStoreFile).exists()

android {
    namespace = "com.clipcells.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.clipcells.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 37
        versionName = "0.20.1"
    }

    signingConfigs {
        if (stableSigningAvailable) {
            create("ci") {
                storeFile = file(ciStoreFile!!)
                storePassword = ciStorePassword
                keyAlias = "clipcells"
                keyPassword = ciStorePassword
            }
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (stableSigningAvailable) {
                signingConfig = signingConfigs.getByName("ci")
            }
        }
        debug {
            if (stableSigningAvailable) {
                signingConfig = signingConfigs.getByName("ci")
            }
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.room:room-runtime:2.7.0")
    implementation("androidx.room:room-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    val composeBom = platform("androidx.compose:compose-bom:2025.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    ksp("androidx.room:room-compiler:2.7.0")
    testImplementation(kotlin("test"))
}
