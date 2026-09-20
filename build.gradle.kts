// 顶层构建文件：只声明整个项目使用的插件及版本，具体应用在 app 模块里。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
