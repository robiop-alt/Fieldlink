plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "no.fieldlink.uploader"
    compileSdk = 35
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    defaultConfig {
        applicationId = "no.fieldlink.uploader"
        minSdk = 29
        targetSdk = 35
        versionCode = System.getenv("OFFSET_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("OFFSET_VERSION_NAME") ?: "0.1.0"
        val defaultToken = System.getenv("OFFSET_DEFAULT_DEVICE_TOKEN") ?: ""
        val escaped = defaultToken.replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "DEFAULT_DEVICE_TOKEN", "\"$escaped\"")
    }
    signingConfigs {
        create("offsetRelease") {
            storeFile = file(System.getenv("OFFSET_KEYSTORE_PATH") ?: "missing-release-keystore.jks")
            storePassword = System.getenv("OFFSET_KEYSTORE_PASSWORD") ?: ""
            keyAlias = System.getenv("OFFSET_KEY_ALIAS") ?: ""
            keyPassword = System.getenv("OFFSET_KEY_PASSWORD") ?: ""
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("offsetRelease")
        }
    }
}

dependencies {
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.media3:media3-transformer:1.6.1")
    implementation("androidx.media3:media3-effect:1.6.1")
}

android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}
