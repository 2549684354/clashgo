package com.clashgo.android.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.clashgo.android.ui.util.TestTags
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun CardSegment(
    isFirst: Boolean,
    isLast: Boolean,
    modifier: Modifier = Modifier,
    color: Color = MiuixTheme.colorScheme.surfaceContainer,
    contentColor: Color = MiuixTheme.colorScheme.onSurfaceContainer,
    cornerRadius: Dp = 16.dp,
    topCornerRadius: Dp = if (isFirst) cornerRadius else 0.dp,
    bottomCornerRadius: Dp = if (isLast) cornerRadius else 0.dp,
    outerHorizontalPadding: Dp = 12.dp,
    outerTopPadding: Dp = 0.dp,
    outerBottomPadding: Dp = 0.dp,
    insidePadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    // 分组卡的「卡片阴影」按段施加：每段带自己那截圆角的 2dp 投影。段与段上下紧贴，
    // 后绘制的段会盖住上一段的接缝阴影，整组视觉上合成一块连续的卡片阴影。
    val shadowShape = RoundedCornerShape(
        topStart = topCornerRadius,
        topEnd = topCornerRadius,
        bottomEnd = bottomCornerRadius,
        bottomStart = bottomCornerRadius,
    )
    val background = if (topCornerRadius == 0.dp && bottomCornerRadius == 0.dp) {
        Modifier.background(color)
    } else {
        Modifier.squircleSurface(color, topCornerRadius, topCornerRadius, bottomCornerRadius, bottomCornerRadius)
    }
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(
                    start = outerHorizontalPadding,
                    end = outerHorizontalPadding,
                    top = outerTopPadding,
                    bottom = outerBottomPadding,
                )
                .shadow(elevation = 2.dp, shape = shadowShape)
                .then(background)
                .padding(insidePadding),
            content = content,
        )
    }
}

class CardItem(
    val key: String,
    val content: @Composable ColumnScope.() -> Unit,
)

fun LazyListScope.groupedCardItems(
    keyPrefix: String,
    items: List<CardItem>,
    outerTopPadding: Dp = 0.dp,
    outerBottomPadding: Dp = 6.dp,
    outerHorizontalPadding: Dp = 12.dp,
    insidePadding: PaddingValues = PaddingValues(0.dp),
) {
    if (items.isEmpty()) return
    val lastIndex = items.lastIndex
    items.forEachIndexed { index, cardItem ->
        item(key = "$keyPrefix:${cardItem.key}") {
            CardSegment(
                isFirst = index == 0,
                isLast = index == lastIndex,
                modifier = Modifier.testTag(TestTags.Settings.entry(cardItem.key)),
                outerHorizontalPadding = outerHorizontalPadding,
                outerTopPadding = if (index == 0) outerTopPadding else 0.dp,
                outerBottomPadding = if (index == lastIndex) outerBottomPadding else 0.dp,
                insidePadding = insidePadding,
                content = cardItem.content,
            )
        }
    }
}
