plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.paradox.snapsort"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.paradox.snapsort"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "2.0.0"
    }

    // 签名配置：CI 环境通过环境变量注入（密钥存 GitHub Secrets），本地/无签名环境产出 unsigned 包
    signingConfigs {
        create("release") {
            val env = System.getenv()
            if (env["KEYSTORE_FILE"] != null) {
                storeFile = file(env["KEYSTORE_FILE"]!!)
                storePassword = env["KEYSTORE_PASSWORD"]
                keyAlias = env["KEY_ALIAS"]
                keyPassword = env["KEY_PASSWORD"]
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (System.getenv("KEYSTORE_FILE") != null) {
                signingConfigs.getByName("release")
            } else {
                null
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
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")

    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // 端侧识别：图像标签（粗分类）+ 中文文字识别（OCR），模型均随 APK 打包，离线可用
    implementation("com.google.mlkit:image-labeling:17.0.9")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    // 刻意不引入：导航库 / OkHttp / Retrofit / Coil / Glide / Room / 图标扩展包
    // 网络 = HttpURLConnection，JSON = org.json，存储 = 文件 + JSON，最大程度压体积
}
