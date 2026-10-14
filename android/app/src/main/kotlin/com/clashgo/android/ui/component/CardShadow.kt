package com.clashgo.android.ui.component

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// 全应用统一的卡片投影：所有卡片一律 2dp。shape 与卡片圆角保持一致，避免阴影和底色错位；
// 深色主题下黑色阴影自然隐去，浅色主题下提供层次。
internal fun Modifier.cardShadow(cornerRadius: Dp): Modifier = shadow(
    elevation = 2.dp,
    shape = RoundedCornerShape(cornerRadius),
)
