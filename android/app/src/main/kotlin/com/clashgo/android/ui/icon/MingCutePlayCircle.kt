// 由 scripts/gen_icons.py 从 MingCute 生成，勿手改。源：regular/media/play_circle.svg
package com.clashgo.android.ui.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

internal val MingCutePlayCircle: ImageVector by lazy {
    ImageVector.Builder(
        name = "PlayCircle",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
        autoMirror = false,
    ).apply {
        addPath(
            pathData = PathParser().parsePathString("M21 12a9 9 0 1 1-18 0a9 9 0 0 1 18 0Z").toNodes(),
            pathFillType = PathFillType.NonZero,
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
        addPath(
            pathData = PathParser().parsePathString("M9.99 8.52s1.253.488 3.018 1.506c1.764 1.02 2.813 1.86 2.813 1.86s-1.021.823-2.814 1.858s-3.016 1.508-3.016 1.508s-.205-1.32-.205-3.366s.205-3.366.205-3.366Z").toNodes(),
            pathFillType = PathFillType.NonZero,
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }.build()
}
