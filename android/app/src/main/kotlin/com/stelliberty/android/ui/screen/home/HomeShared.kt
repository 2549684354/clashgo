package com.stelliberty.android.ui.screen.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
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

// 首页卡片标题行：icon 与文字同色（onSurface），供概览/系统/工具卡共用。
@Composable
internal fun CardTitle(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 14.sp,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            fontSize = fontSize,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
        )
    }
}

// 首页分区标题：icon 与文字同色同排。样式复刻 miuix SmallTitle（subtitle 字号 + 其默认
// insideMargin 28/8），只是把无前缀的纯文本换成「icon + 文字」，保证各分区观感一致。
@Composable
internal fun HomeSectionTitle(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(horizontal = 28.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onBackgroundVariant,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = MiuixTheme.textStyles.subtitle,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
        )
    }
}

@Composable
internal fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text(text = value, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurface)
    }
}
