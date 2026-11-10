plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.ginsengidentifier"
    compileSdk = 34

    signingConfigs {
        // Only created when a real keystore is supplied via -P properties.
        val storePath = (project.findProperty("RELEASE_STORE_FILE") as String?)
        if (storePath != null) {
            create("releaseKey") {
                storeFile = rootProject.file(storePath)
                storePassword = project.findProperty("RELEASE_STORE_PASSWORD") as String?
                keyAlias = project.findProperty("RELEASE_KEY_ALIAS") as String?
                keyPassword = project.findProperty("RELEASE_KEY_PASSWORD") as String?
            }
        }
    }

    defaultConfig {
        applicationId = "com.example.ginsengidentifier"
        minSdk = 24
        targetSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        versionCode = 3
        versionName = "2.2"

        // FCM 凭据通过 Gradle 属性注入，避免把 google-services.json 塞进仓库：
        //   gradle.properties:
        //     FCM_PROJECT_ID=xxx
        //     FCM_APP_ID=1:123:android:abc
        //     FCM_API_KEY=AIza...
        //     FCM_SENDER_ID=123
        fun prop(name: String): String = (project.findProperty(name) as String?) ?: ""
        buildConfigField("String", "FCM_PROJECT_ID", "\"${prop("FCM_PROJECT_ID")}\"")
        buildConfigField("String", "FCM_APP_ID", "\"${prop("FCM_APP_ID")}\"")
        buildConfigField("String", "FCM_API_KEY", "\"${prop("FCM_API_KEY")}\"")
        buildConfigField("String", "FCM_SENDER_ID", "\"${prop("FCM_SENDER_ID")}\"")
    }

    buildTypes {
        release {
            // Demo signing: falls back to the standard debug keystore so a
            // release APK can be produced without committing any secret.
            // Provide a real key with:
            //   -PRELEASE_STORE_FILE=... -PRELEASE_STORE_PASSWORD=...
            //   -PRELEASE_KEY_ALIAS=...   -PRELEASE_KEY_PASSWORD=...
            val storePath = (project.findProperty("RELEASE_STORE_FILE") as String?)
            signingConfig = if (storePath != null) {
                signingConfigs.getByName("releaseKey")
            } else {
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.service)

    // 常驻扫描 / 悬浮窗 / 实时流
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.java.websocket)

    // FCM：没有 google-services.json 也能编译；凭据通过 Gradle 属性注入
    implementation(libs.firebase.messaging)

    // Duolingo 风格桌面小组件
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    implementation(libs.tensorflow.lite)
    implementation(libs.tensorflow.lite.support)

    implementation(libs.coil.compose)
    implementation(libs.accompanist.permissions)

    debugImplementation(libs.androidx.compose.ui.tooling)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
}
