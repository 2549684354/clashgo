package com.stelliberty.android.ui.screen.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import com.stelliberty.android.R
import com.stelliberty.android.ui.component.CardItem
import com.stelliberty.android.ui.component.groupedCardItems
import top.yukonga.miuix.kmp.preference.ArrowPreference

// 「关于 → 开放源代码许可 → 更多」：本项目与内核之外的全部依赖许可集中在这里。
@Composable
fun OssMoreScreen(
    onBack: () -> Unit = {},
    onOpenUrl: (String) -> Unit = {},
) {
    val projects = remember {
        listOf(
            "miuix" to "https://github.com/compose-miuix-ui/miuix",
            "scripta" to "https://github.com/YuKongA/scripta",
            "AndroidHiddenApiBypass" to "https://github.com/LSPosed/AndroidHiddenApiBypass",
            "AndroidX" to "https://github.com/androidx/androidx",
            "Koin" to "https://github.com/InsertKoinIO/koin",
            "Kotlin" to "https://github.com/JetBrains/kotlin",
            "kotlinx.collections.immutable" to "https://github.com/Kotlin/kotlinx.collections.immutable",
            "kotlinx.coroutines" to "https://github.com/Kotlin/kotlinx.coroutines",
            "kotlinx-datetime" to "https://github.com/Kotlin/kotlinx-datetime",
            "kotlinx.serialization" to "https://github.com/Kotlin/kotlinx.serialization",
            "Ktor" to "https://github.com/ktorio/ktor",
            "quickie" to "https://github.com/G00fY2/quickie",
        )
    }

    SettingsSubPage(title = stringResource(R.string.about_open_source), onBack = onBack) {
        item { Spacer(Modifier.height(12.dp)) }
        groupedCardItems(
            keyPrefix = "oss_more",
            items = projects.map { (name, url) ->
                CardItem(name) {
                    ArrowPreference(
                        title = name,
                        summary = url.removePrefix("https://"),
                        onClick = { onOpenUrl(url) },
                    )
                }
            },
        )
    }
}
