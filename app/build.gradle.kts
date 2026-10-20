import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Keystore de release gerada no CI (variáveis de ambiente). Sem elas, o build
// release cai no keystore de debug padrão — o APK continua instalável.
val ciKeystorePath: String? = System.getenv("XISO_KEYSTORE")
val ciKeystoreFile: File? = ciKeystorePath?.let { File(it) }?.takeIf { it.exists() }

android {
    namespace = "com.deivid22srk.xisotool"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.deivid22srk.xisotool"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (ciKeystoreFile != null) {
            create("ciRelease") {
                storeFile = ciKeystoreFile
                storePassword = System.getenv("XISO_KEYSTORE_PASSWORD") ?: "android"
                keyAlias = System.getenv("XISO_KEY_ALIAS") ?: "xiso"
                keyPassword = System.getenv("XISO_KEY_PASSWORD") ?: "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (ciKeystoreFile != null) {
                signingConfigs.getByName("ciRelease")
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
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
