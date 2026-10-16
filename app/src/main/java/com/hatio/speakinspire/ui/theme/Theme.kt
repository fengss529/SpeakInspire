package com.hatio.speakinspire.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * MD3 配色。取自 v0.4 原型的设计令牌——
 * 保持代码与原型一致，改动时两边要同步。
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF625B71),
    background = Color(0xFFFDFBFF),
    onBackground = Color(0xFF1C1B1F),
    surface = Color(0xFFFDFBFF),
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454F),
    outline = Color(0xFF79747E),
)

/** 状态色——对应 8 个派生状态 */
object StatusColors {
    val wait = Color(0xFF8B6FC7)      // 待深化
    val ongoing = Color(0xFFB9A6F5)   // 深化中
    val deepened = Color(0xFF7C6BB0)  // 已深化
    val articlEd = Color(0xFF6750A4)  // 已成文章
    val topic = Color(0xFF5B8DEF)     // 待开拍
    val shot = Color(0xFF7FC8A9)      // 已拍摄
    val parked = Color(0xFFA0A0A0)    // 先留着
    val stale = Color(0xFFB0B0B0)     // 已过时
    val mine = Color(0xFF6750A4)      // 「你说的」
    val aiFill = Color(0xFFFFF4E0)    // 「AI 补」底色
}

private val AppTypography = Typography(
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun SpeakInspireTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // MVP 阶段只做浅色——深色留到 P2，先把浅色做扎实
    val colors = LightColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view)
                .isAppearanceLightStatusBars = true
        }
    }

    MaterialTheme(
        colorScheme = colors,
        typography = AppTypography,
        content = content,
    )
}