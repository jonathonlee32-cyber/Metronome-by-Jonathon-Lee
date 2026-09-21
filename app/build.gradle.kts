plugins {
    // Android Application 插件：把本模块编译成一个可安装的 APK。
    alias(libs.plugins.android.application)
    // Kotlin 支持。
    alias(libs.plugins.kotlin.android)
    // Kotlin 2.0 起，Compose 编译器通过这个独立插件启用。
    alias(libs.plugins.kotlin.compose)
}

android {
    // 代码所在的包名空间（生成 R 类、BuildConfig 用）。
    namespace = "com.example.musicpractice"
    // 用哪个 Android SDK 编译。本机已安装 Platform 35。
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.musicpractice"
        // 支持 Android 5.0 及以上，覆盖面最广。
        minSdk = 21
        targetSdk = 35
        versionCode = 7
        versionName = "2.1"
    }

    buildTypes {
        release {
            // 初学者项目不配置混淆，保持简单。
            isMinifyEnabled = false
        }
    }

    // 用 JDK 17 的字节码目标编译（AGP 8 的推荐设置，本机 JDK 21 可以编译到 17）。
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        // 开启 Compose 支持。
        compose = true
    }
}

dependencies {
    // 只保留 Compose 运行所必需的官方库，没有第三方依赖。
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    // Compose BOM 统一管理下面这些 Compose 库的版本。
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    // 纯 JVM 单元测试：练习记录的日期切分、时长格式化和统计汇总都是纯逻辑，直接跑在电脑上。
    testImplementation(libs.junit)
    // org.json 是 Android 自带的类，JVM 测试里没有实现，这里补一个真实实现用于跑序列化测试。
    testImplementation(libs.json)
}
