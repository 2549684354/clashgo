package com.stelliberty.android.ui.screen.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal const val WatermarkHeightFraction = 0.52f

internal const val WatermarkFillAlpha = 0.22f

internal val WatermarkStrokeWidth = 1.5.dp

// 首页卡片「浮」在页面上的体积感来自柔和投影。深色主题下黑色阴影自然隐去，
// 浅色主题下提供层次；shape 与所在卡片的圆角保持一致，避免阴影和底色错位。
internal fun Modifier.cardShadow(cornerRadius: Dp): Modifier = shadow(
    elevation = CardShadowElevation,
    shape = RoundedCornerShape(cornerRadius),
)

private val CardShadowElevation = 4.dp

// miuix Card 的默认圆角，首页所有走默认值的卡片共用。
internal val HomeCardCornerRadius = 16.dp

@Composable
internal fun BadgeLabel(text: String, color: Color = MiuixTheme.colorScheme.primary) {
    Text(
        text = text,
        modifier = Modifier
            .squircleBackground(color.copy(alpha = BadgeContainerAlpha), 6.dp)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = color,
    )
}

private const val BadgeContainerAlpha = 0.16f

@Composable
internal fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text(text = value, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurface)
    }
}
