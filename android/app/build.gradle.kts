import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "ua.ledpanel"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "ua.ledpanel"
        minSdk = 26
        targetSdk = 37
        // Тег релізу на GitHub — v<versionCode>: за ним застосунок і знаходить оновлення.
        versionCode = 2
        versionName = "1.1"
    }

    // Підпис тим самим ключем, що й у встановленого застосунку, інакше оновлення не стане поверх.
    val keystore = rootProject.file("keystore.properties")
    signingConfigs {
        if (keystore.exists()) {
            create("release") {
                val p = Properties().apply { keystore.inputStream().use { load(it) } }
                storeFile = file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.work.runtime.ktx)
}
