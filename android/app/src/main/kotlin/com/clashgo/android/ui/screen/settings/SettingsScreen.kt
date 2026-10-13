package com.clashgo.android.ui.screen.settings

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.clashgo.android.BuildConfig
import com.clashgo.android.R
import com.clashgo.android.ui.component.AdaptiveTopAppBar
import com.clashgo.android.ui.component.CardItem
import com.clashgo.android.ui.component.blur.BlurredBar
import com.clashgo.android.ui.component.blur.rememberBlurBackdrop
import com.clashgo.android.ui.component.groupedCardItems
import com.clashgo.android.ui.util.WideContentBox
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

private fun LazyListScope.settingsGroup(
    keyPrefix: String,
    @StringRes titleRes: Int,
    outerBottomPadding: Dp = 6.dp,
    build: MutableList<CardItem>.() -> Unit,
) {
    item(key = "$keyPrefix:title") {
        SmallTitle(text = stringResource(titleRes))
    }
    groupedCardItems(keyPrefix = keyPrefix, items = buildList(build), outerBottomPadding = outerBottomPadding)
}

// 搜索索引：每个可跳转的设置入口一条；keywords 是中英文补充词（不进界面，只参与匹配）。
private class SettingsSearchTarget(
    val key: String,
    @StringRes val titleRes: Int,
    @StringRes val summaryRes: Int,
    val keywords: List<String>,
    val navigate: () -> Unit,
)

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 0.dp,
    onNavigateThemeSettings: () -> Unit = {},
    onNavigateClashFeatures: () -> Unit = {},
    onNavigateOverrides: () -> Unit = {},
    onNavigateFileManager: () -> Unit = {},
    onNavigateAppBehavior: () -> Unit = {},
    onNavigateAbout: () -> Unit = {},
    onNavigateDataManagement: () -> Unit = {},
    onNavigateAgeKey: () -> Unit = {},
) {
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface
    val queryState = rememberTextFieldState()
    val context = LocalContext.current
    val query = queryState.text.toString().trim()
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()

    // 有输入时按返回键先退出搜索态（清空并释放焦点），再按一次才走页面的返回逻辑。
    BackHandler(enabled = query.isNotEmpty()) {
        focusManager.clearFocus()
        queryState.setTextAndPlaceCursorAtEnd("")
    }
    // 滚动列表时收起键盘并释放焦点，避免光标一直滞留在搜索框。
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) focusManager.clearFocus()
    }

    val searchTargets = listOf(
        SettingsSearchTarget(
            "theme", R.string.settings_theme_title, R.string.settings_theme_summary,
            listOf("theme", "主题", "外觀", "外观", "深色", "淺色", "浅色", "暗色", "monet", "動態取色", "动态取色", "字體", "字体", "壁紙", "壁纸"),
            onNavigateThemeSettings,
        ),
        SettingsSearchTarget(
            "clashFeatures", R.string.clash_features_title, R.string.clash_features_summary,
            listOf("clash", "core", "內核", "内核", "feature", "特性", "dns", "嗅探", "sniffer", "udp", "tun", "stack", "協議", "协议", "gvisor", "系統代理", "系统代理"),
            onNavigateClashFeatures,
        ),
        SettingsSearchTarget(
            "overrides", R.string.override_title, R.string.settings_overrides_summary,
            listOf("override", "覆寫", "覆写", "规则", "rule", "rewrite", "重寫", "重写", "腳本", "脚本", "混合", "merge", "script"),
            onNavigateOverrides,
        ),
        SettingsSearchTarget(
            "fileManager", R.string.settings_file_manager, R.string.settings_file_manager_summary,
            listOf("file", "文件", "檔案", "管理", "manager", "編輯", "编辑", "配置", "profile", "yaml"),
            onNavigateFileManager,
        ),
        SettingsSearchTarget(
            "appBehavior", R.string.app_behavior_title, R.string.app_behavior_summary,
            listOf("app", "behavior", "應用", "应用", "行为", "行為", "自啟", "自启", "boot", "language", "語言", "语言", "haptic", "振動", "振动", "震動", "震動", "通知", "notification", "任務卡片", "任务卡片"),
            onNavigateAppBehavior,
        ),
        SettingsSearchTarget(
            "about", R.string.settings_about, R.string.settings_about_summary,
            listOf("about", "關於", "关于", "版本", "version", "update", "更新", "license", "許可", "许可", "開源", "开源"),
            onNavigateAbout,
        ),
        SettingsSearchTarget(
            "dataManagement", R.string.data_management_title, R.string.data_management_summary,
            listOf("data", "數據", "数据", "backup", "備份", "备份", "恢復", "恢复", "還原", "还原", "導入", "导入", "導出", "导出", "webdav", "清理", "cache"),
            onNavigateDataManagement,
        ),
        SettingsSearchTarget(
            "ageKey", R.string.age_key_title, R.string.age_key_summary,
            listOf("age", "key", "密鑰", "密钥", "加密", "encrypt", "解密", "decrypt"),
            onNavigateAgeKey,
        ),
    )

    Scaffold(
        modifier = modifier,
        topBar = {
            BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                AdaptiveTopAppBar(
                    title = stringResource(R.string.settings_title),
                    color = barColor,
                    scrollBehavior = scrollBehavior,
                )
            }
        },
    ) { innerPadding ->
        WideContentBox { sidePadding ->
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                    .pointerInput(Unit) {
                        // 点列表空白处释放搜索框焦点。
                        detectTapGestures { focusManager.clearFocus() }
                    }
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    start = sidePadding,
                    end = sidePadding,
                    bottom = bottomPadding,
                ),
            ) {
                item(key = "settings_search") {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        TextField(
                            state = queryState,
                            modifier = Modifier.fillMaxWidth(),
                            label = stringResource(R.string.settings_search_hint),
                            useLabelAsPlaceholder = true,
                            lineLimits = TextFieldLineLimits.SingleLine,
                        )
                    }
                }

                if (query.isEmpty()) {
                    settingsGroup("settings_personalization", R.string.settings_group_personalization) {
                        add(CardItem("theme") {
                            ArrowPreference(
                                title = stringResource(R.string.settings_theme_title),
                                summary = stringResource(R.string.settings_theme_summary),
                                onClick = onNavigateThemeSettings,
                            )
                        })
                    }

                    settingsGroup("settings_clash", R.string.settings_group_clash) {
                        add(CardItem("clashFeatures") {
                            ArrowPreference(
                                title = stringResource(R.string.clash_features_title),
                                summary = stringResource(R.string.clash_features_summary),
                                onClick = onNavigateClashFeatures,
                            )
                        })
                        add(CardItem("overrides") {
                            ArrowPreference(
                                title = stringResource(R.string.override_title),
                                summary = stringResource(R.string.settings_overrides_summary),
                                onClick = onNavigateOverrides,
                            )
                        })
                        add(CardItem("fileManager") {
                            ArrowPreference(
                                title = stringResource(R.string.settings_file_manager),
                                summary = stringResource(R.string.settings_file_manager_summary),
                                onClick = onNavigateFileManager,
                            )
                        })
                    }

                    settingsGroup("settings_application", R.string.settings_group_application) {
                        add(CardItem("appBehavior") {
                            ArrowPreference(
                                title = stringResource(R.string.app_behavior_title),
                                summary = stringResource(R.string.app_behavior_summary),
                                onClick = onNavigateAppBehavior,
                            )
                        })
                        add(CardItem("about") {
                            ArrowPreference(
                                title = stringResource(R.string.settings_about),
                                summary = "Clash Go v${BuildConfig.VERSION_NAME}",
                                onClick = onNavigateAbout,
                            )
                        })
                    }

                    settingsGroup("settings_maintenance", R.string.settings_group_maintenance, outerBottomPadding = 12.dp) {
                        add(CardItem("dataManagement") {
                            ArrowPreference(
                                title = stringResource(R.string.data_management_title),
                                summary = stringResource(R.string.data_management_summary),
                                onClick = onNavigateDataManagement,
                            )
                        })
                        add(CardItem("ageKey") {
                            ArrowPreference(
                                title = stringResource(R.string.age_key_title),
                                summary = stringResource(R.string.age_key_summary),
                                onClick = onNavigateAgeKey,
                            )
                        })
                    }
                } else {
                    // 标题/摘要取当前语言做包含匹配（忽略大小写），keywords 兜底同义词。
                    val q = query.lowercase()
                    val matched = searchTargets.filter { t ->
                        context.getString(t.titleRes).lowercase().contains(q) ||
                            context.getString(t.summaryRes).lowercase().contains(q) ||
                            t.keywords.any { it.lowercase().contains(q) }
                    }
                    if (matched.isEmpty()) {
                        item(key = "search_empty") {
                            Text(
                                text = stringResource(R.string.settings_search_no_results),
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 24.dp),
                                textAlign = TextAlign.Center,
                            )
                        }
                    } else {
                        matched.forEach { t ->
                            groupedCardItems(
                                keyPrefix = "search_${t.key}",
                                items = listOf(
                                    CardItem(t.key) {
                                        ArrowPreference(
                                            title = stringResource(t.titleRes),
                                            summary = stringResource(t.summaryRes),
                                            onClick = t.navigate,
                                        )
                                    },
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}
