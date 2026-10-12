// 由 scripts/gen_icons.py 从 MingCute 生成，勿手改。源：regular/files/link.svg
package com.stelliberty.android.ui.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

internal val MingCuteLink: ImageVector by lazy {
    ImageVector.Builder(
        name = "Link",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
        autoMirror = false,
    ).apply {
        addPath(
            pathData = PathParser().parsePathString("M11.6464 5.98873L10.2322 4.57452C8.67013 3.01242 6.13747 3.01242 4.57538 4.57452C3.01328 6.13662 3.01328 8.66928 4.57538 10.2314L7.4038 13.0598C8.9659 14.6219 11.4986 14.6219 13.0607 13.0598M12.3535 18.0095L13.7678 19.4238C15.3299 20.9859 17.8625 20.9859 19.4246 19.4238C20.9867 17.8617 20.9867 15.329 19.4246 13.7669L16.5962 10.9385C15.0341 9.37638 12.5014 9.37638 10.9393 10.9385").toNodes(),
            pathFillType = PathFillType.NonZero,
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Miter,
        )
    }.build()
}
