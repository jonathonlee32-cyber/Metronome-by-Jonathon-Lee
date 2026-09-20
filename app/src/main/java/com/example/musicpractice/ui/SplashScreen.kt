package com.example.musicpractice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.musicpractice.R
import com.example.musicpractice.ui.theme.MusicPracticeTheme

/**
 * 启动页底色，与 res/values/colors.xml 里的 splash_background 保持一致。
 * 窗口背景也用同一个颜色，所以从系统窗口切到这一屏时看不出接缝。
 */
private val SplashBackground = Color(0xFF0C1E56)

/** 启动页文字颜色：深蓝底上用近白色，保证对比度。 */
private val SplashForeground = Color(0xFFFFFFFF)

/**
 * 启动页：深蓝底 + 四行居中的文字。
 *
 * 这里只负责"长什么样"，不负责"显示多久"。停留时间由 MainActivity 控制，
 * 时间一到就把这一屏换成节拍器界面（[MetronomeScreen]）。
 */
@Composable
fun SplashScreen(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SplashBackground),
        // Box 的 contentAlignment 让里面的 Column 在屏幕正中间。
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 第一行：应用名。
            Text(
                text = stringResource(R.string.splash_title),
                color = SplashForeground,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 第二行：作者。
            Text(
                text = stringResource(R.string.splash_author),
                color = SplashForeground.copy(alpha = 0.85f),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 第三行：学号 / 单位信息。
            Text(
                text = stringResource(R.string.splash_belonging),
                color = SplashForeground.copy(alpha = 0.8f),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 第四行：版本号和日期。
            Text(
                text = stringResource(R.string.splash_version),
                color = SplashForeground.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun SplashScreenPreview() {
    MusicPracticeTheme {
        SplashScreen()
    }
}
