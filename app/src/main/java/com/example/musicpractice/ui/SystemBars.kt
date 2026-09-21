package com.example.musicpractice.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Android 6.0 以下不支持"深色状态栏图标"时用的底色。
 * 取值和启动页的深蓝一致（res/values/colors.xml 里的 splash_background），
 * 白色图标压在上面看得清。
 */
private const val DarkStatusBarFallbackArgb = 0xFF0C1E56.toInt()

/**
 * 让状态栏图标的明暗跟着当前画面走。
 *
 * 为什么需要这一步：Activity 用的 XML 主题是 `android:Theme.Material.Light.NoActionBar`，
 * 它不设置 `windowLightStatusBar`，所以系统默认把状态栏图标画成**白色**。
 * 从 Android 15（targetSdk 35）起系统强制全屏，状态栏是透明的、直接盖在应用内容上，
 * 浅色主题下就是"浅色背景 + 白色图标"，等于看不见 —— 这个方法就是来修这个问题的。
 *
 * @param darkBackground 当前画面顶部是不是深色底。启动页是深蓝底，所以为 true；
 *   功能页跟随主题：深色主题 true，浅色主题 false。
 * @param backgroundColor 当前画面的底色。仅在 Android 15 以下需要（那时状态栏还不透明，
 *   要把它的底色设成和页面一致，深色图标才看得清）；Android 15 起状态栏透明，颜色由系统忽略。
 */
@Composable
fun ApplySystemBarAppearance(
    darkBackground: Boolean,
    backgroundColor: Color
) {
    val view = LocalView.current
    val backgroundColorArgb = backgroundColor.toArgb()

    SideEffect {
        val window = view.context.findActivity()?.window ?: return@SideEffect

        // Android 6.0 起才有"深色状态栏图标"这个能力；更低版本只能保持深底 + 白图标。
        val canUseDarkIcons = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
        val useLightIcons = darkBackground || !canUseDarkIcons

        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !useLightIcons

        // Android 15 起系统强制全屏：状态栏透明，底色就是页面自己画的颜色，
        // 设 statusBarColor 已经没有任何效果（而且被标记为废弃）。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            @Suppress("DEPRECATION")
            window.statusBarColor = if (useLightIcons) DarkStatusBarFallbackArgb else backgroundColorArgb
        }

        // 导航栏只在 Android 15 及以上处理：那时它是透明的、背景就是本页内容，图标明暗必须
        // 和内容一致；更早的系统导航栏底色来自主题（通常是深色），交回系统处理最稳。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            controller.isAppearanceLightNavigationBars = !useLightIcons
        }
    }
}

/** 从任意 Context 往上找到宿主 Activity（设置系统栏要用它的 window）。 */
internal fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
