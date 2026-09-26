plugins {
    // Android Application 插件：把本模块编译成一个可安装的 APK。
    alias(libs.plugins.android.application)
    // Kotlin 支持。
    alias(libs.plugins.kotlin.android)
    // Kotlin 2.0 起，Compose 编译器通过这个独立插件启用。
    alias(libs.plugins.kotlin.compose)
    // KSP：Room 的注解处理器挂在它上面（比老的 kapt 快得多）。
    alias(libs.plugins.ksp)
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
        // 版本号规则：每次改动功能 +1（v2.2 -> v2.2.1），versionName 与启动页显示的版本一致。
        versionCode = 20
        versionName = "5.1"
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

    testOptions {
        // 纯 JVM 单元测试里没有 Android 的 Log 实现。returnDefaultValues 让 android.util.Log
        // 这类只有签名的方法返回默认值（Log.w 变成空操作），这样"配置文件被改坏时回落到默认值"
        // 这种会走到日志分支的路径也能被测到。只影响单元测试，不影响 App 行为。
        unitTests.isReturnDefaultValues = true
    }
}

// Room 把数据库的表结构导出成 JSON 存进仓库（app/schemas/）：
// v5.1 起录音库有了版本迁移（v1 → v2 加音准分析的两张表），导出 schema 之后，
// "当初的表长什么样"有据可查，以后写迁移时能直接对着看，也不容易写错。
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
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

    // 乐谱项目的本地数据库：Room（Android 官方库，底层就是系统自带的 SQLite，
    // 完全离线、不需要任何权限、不上传任何数据）。编译器在编译期由 KSP 生成代码。
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // 纯 JVM 单元测试：练习记录的日期切分、时长格式化和统计汇总都是纯逻辑，直接跑在电脑上。
    testImplementation(libs.junit)
    // org.json 是 Android 自带的类，JVM 测试里没有实现，这里补一个真实实现用于跑序列化测试。
    testImplementation(libs.json)
}
