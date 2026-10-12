// 由 scripts/gen_icons.py 从 MingCute 生成，勿手改。源：regular/system/refresh_1.svg
package com.stelliberty.android.ui.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

internal val MingCuteRefresh1: ImageVector by lazy {
    ImageVector.Builder(
        name = "Refresh1",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
        autoMirror = false,
    ).apply {
        addPath(
            pathData = PathParser().parsePathString("M19.9604 10.4097L18.2523 10.0285C18.1445 10.0044 18.083 10.1473 18.1746 10.209L20.8482 12.0104C20.9141 12.0549 21.0027 12.0083 21.0021 11.9288C20.9993 11.537 20.9706 11.1486 20.9175 10.7658C20.435 7.29054 17.9316 4.27169 14.3295 3.30652C9.52833 2.02004 4.5933 4.86928 3.30682 9.67048C2.02034 14.4717 4.86959 19.4067 9.67078 20.6932C13.4947 21.7178 17.4035 20.119 19.4863 17").toNodes(),
            pathFillType = PathFillType.NonZero,
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }.build()
}
