package com.clashgo.android.ui.screen.subscription

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clashgo.android.R
import com.clashgo.android.domain.model.Subscription
import com.clashgo.android.ui.component.AdaptiveTopAppBar
import com.clashgo.android.ui.component.ListPopupDefaults.MenuPositionProvider
import com.clashgo.android.ui.component.blur.BlurredBar
import com.clashgo.android.ui.component.blur.rememberBlurBackdrop
import com.clashgo.android.ui.icon.AppIcons
import com.clashgo.android.ui.theme.StatusColors
import com.clashgo.android.ui.util.TestTags
import com.clashgo.android.ui.util.WideContentBox
import com.clashgo.android.util.FormatUtils
import com.clashgo.android.util.formatEpochMillisAsLocal
import com.clashgo.android.viewmodel.ProfileOperation
import com.clashgo.android.viewmodel.SubscriptionViewModel
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowListPopup
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
fun SubscriptionScreen(
    viewModel: SubscriptionViewModel,
    bottomPadding: Dp = 0.dp,
    onPickFile: () -> Unit = {},
    onNavigateUrl: () -> Unit = {},
    onScanQr: (() -> Unit)? = null,
    onNavigateEdit: (uuid: String) -> Unit = {},
    onActiveChanged: (() -> Unit)? = null,
    isNavTop: Boolean = true,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = MiuixScrollBehavior()

    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface

    Scaffold(
        topBar = {
            BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                AdaptiveTopAppBar(
                    title = stringResource(R.string.subscription_title),
                    color = barColor,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {},
                    actions = {
                        if (uiState.subscriptions.any { !it.isLocalFile }) {
                            IconButton(
                                onClick = { viewModel.updateAllSubscriptions() },
                                modifier = Modifier.testTag(TestTags.Subscription.UPDATE_ALL),
                                enabled = !uiState.isLoading,
                            ) {
                                Icon(
                                    imageVector = AppIcons.Refresh,
                                    contentDescription = stringResource(R.string.subscription_update_all),
                                    tint = MiuixTheme.colorScheme.onSurface,
                                )
                            }
                        }
                        Box {
                            var showAddMenu by remember { mutableStateOf(false) }
                            IconButton(
                                onClick = { showAddMenu = true },
                                modifier = Modifier.testTag(TestTags.Subscription.ADD),
                            ) {
                                Icon(
                                    imageVector = AppIcons.Add,
                                    contentDescription = stringResource(R.string.subscription_add),
                                    tint = MiuixTheme.colorScheme.onSurface,
                                )
                            }
                            // 「添加配置」直接弹出二级菜单：文件 / 链接 / 扫码，不再跳中间页。
                            WindowListPopup(
                                show = showAddMenu,
                                popupPositionProvider = MenuPositionProvider,
                                alignment = PopupPositionProvider.Align.TopEnd,
                                onDismissRequest = { showAddMenu = false },
                            ) {
                                ListPopupColumn {
                                    val entries = buildList {
                                        add(
                                            AddMenuEntry(
                                                title = stringResource(R.string.subscription_file),
                                                icon = AppIcons.SubscriptionFile,
                                                onClick = {
                                                    viewModel.clearError()
                                                    onPickFile()
                                                },
                                            ),
                                        )
                                        add(
                                            AddMenuEntry(
                                                title = stringResource(R.string.subscription_url),
                                                icon = AppIcons.SubscriptionLink,
                                                onClick = {
                                                    viewModel.clearError()
                                                    onNavigateUrl()
                                                },
                                            ),
                                        )
                                        if (onScanQr != null) {
                                            add(
                                                AddMenuEntry(
                                                    title = stringResource(R.string.subscription_qr),
                                                    icon = AppIcons.SubscriptionQr,
                                                    onClick = {
                                                        viewModel.clearError()
                                                        onScanQr()
                                                    },
                                                ),
                                            )
                                        }
                                    }
                                    entries.forEachIndexed { index, entry ->
                                        // DropdownImpl 不带图标位，这里用同一间距规格的自定义行：
                                        // 20dp 图标 + 12dp 间距 + 16sp 文字，与 miuix 菜单项观感一致。
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    showAddMenu = false
                                                    entry.onClick()
                                                }
                                                .padding(horizontal = 16.dp, vertical = 14.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Icon(
                                                imageVector = entry.icon,
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp),
                                                tint = MiuixTheme.colorScheme.onSurface,
                                            )
                                            Spacer(Modifier.width(12.dp))
                                            Text(
                                                text = entry.title,
                                                fontSize = 16.sp,
                                                color = MiuixTheme.colorScheme.onSurface,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        WideContentBox { sidePadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    bottom = bottomPadding,
                    start = sidePadding,
                    end = sidePadding,
                ),
            ) {
                if (uiState.subscriptions.isNotEmpty()) {
                    item(key = "top_padding") {
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }

                if (uiState.error.isNotEmpty()) {
                    item(key = "error") {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 12.dp),
                            insideMargin = PaddingValues(16.dp),
                        ) {
                            Text(
                                text = uiState.error,
                                color = StatusColors.danger,
                            )
                        }
                    }
                }

                if (uiState.subscriptions.isEmpty()) {
                    item(key = "empty") {
                        Column(
                            modifier = Modifier.fillParentMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.subscription_no_config),
                                fontSize = 16.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            Text(
                                text = stringResource(R.string.subscription_tap_add),
                                modifier = Modifier.padding(top = 6.dp),
                                fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }

                items(uiState.subscriptions, key = { it.id }) { sub ->
                    SubscriptionItem(
                        subscription = sub,
                        isActive = sub.id == uiState.currentId,
                        isLoading = uiState.isLoading,
                        onSelect = {
                            viewModel.setActive(sub.id)
                            onActiveChanged?.invoke()
                        },
                        onRefresh = { viewModel.fetchSubscription(sub.id) },
                        onDelete = {
                            viewModel.removeSubscription(sub.id) { onActiveChanged?.invoke() }
                        },
                        onEdit = { onNavigateEdit(sub.id) },
                    )
                }
            }
        }
    }

    val title = when (uiState.operation) {
        ProfileOperation.Update -> stringResource(R.string.subscription_update_config)
        else -> stringResource(R.string.subscription_import_config)
    }
    // 本页被 push 出去的子页（URL 导入/编辑等）会渲染它们自己的进度弹窗；
    // NavDisplay 保留底层页面的组合，这里不再显示，避免同一进度弹出两个。
    ImportProgressDialog(
        show = isNavTop && uiState.importProgress != null,
        step = uiState.importProgress?.let { importStepLabel(it) } ?: stringResource(R.string.common_processing),
        title = title,
        onCancel = { viewModel.cancelCurrentUpdate() },
    )
}

@Composable
private fun SubscriptionItem(
    subscription: Subscription,
    isActive: Boolean,
    isLoading: Boolean,
    onSelect: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    val displayName = subscription.name.ifBlank { stringResource(R.string.subscription_config) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            // ambient 置透明：elevation 阴影的四周均匀分量会让卡片像垫在灰板上，只留向下投影。
            .shadow(
                elevation = 2.dp,
                shape = RoundedCornerShape(16.dp),
                ambientColor = Color.Transparent,
                spotColor = Color.Black.copy(alpha = 0.22f),
            )
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp)
            .testTag(TestTags.Subscription.item(subscription.id)),
        insideMargin = PaddingValues(16.dp),
        onClick = onSelect,
        pressFeedbackType = PressFeedbackType.Sink,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = subscription.name.ifBlank { stringResource(R.string.subscription_config) },
                modifier = Modifier.weight(1f),
                fontSize = 17.sp,
                fontWeight = FontWeight(550),
                color = MiuixTheme.colorScheme.onSurface,
            )
            if (isActive) {
                val activeColor = StatusColors.healthy
                Text(
                    text = stringResource(R.string.subscription_in_use),
                    fontSize = 12.sp,
                    fontWeight = FontWeight(750),
                    color = activeColor,
                    modifier = Modifier
                        .squircleBackground(activeColor.copy(alpha = 0.15f), 6.dp)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Row {

            Column(
                modifier = Modifier.wrapContentSize()
            ) {
                val traffic = subscription.trafficInfo
                if (traffic != null && traffic.total > 0) {
                    Text(
                        text = stringResource(
                            R.string.subscription_used_traffic,
                            FormatUtils.formatBytes(traffic.used),
                            FormatUtils.formatBytes(traffic.total)
                        ),
                        modifier = Modifier.padding(top = 2.dp),
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.subscription_no_traffic),
                        modifier = Modifier.padding(top = 2.dp),
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }

                Spacer(Modifier.weight(1f))

                val updatedAt = (subscription.lastUpdatedAt ?: subscription.createdAt).toEpochMilliseconds()
                if (updatedAt > 0) {
                    Text(
                        text = stringResource(R.string.subscription_updated_at, formatEpochMillisAsLocal(updatedAt)),
                        modifier = Modifier.padding(top = 2.dp),
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            IconButton(
                onClick = onEdit,
                minHeight = 35.dp,
                minWidth = 35.dp,
                backgroundColor = MiuixTheme.colorScheme.secondaryContainer,
            ) {
                Icon(
                    modifier = Modifier.size(20.dp),
                    imageVector = AppIcons.More,
                    contentDescription = stringResource(R.string.common_edit),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = onRefresh,
                enabled = !isLoading && !subscription.isLocalFile,
                minHeight = 35.dp,
                minWidth = 35.dp,
                backgroundColor = MiuixTheme.colorScheme.secondaryContainer,
            ) {
                Icon(
                    modifier = Modifier.size(20.dp),
                    imageVector = AppIcons.Refresh,
                    contentDescription = stringResource(R.string.common_update),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = { showDeleteDialog = true },
                minHeight = 35.dp,
                minWidth = 35.dp,
                backgroundColor = MiuixTheme.colorScheme.secondaryContainer,
            ) {
                Icon(
                    modifier = Modifier.size(20.dp),
                    imageVector = AppIcons.Delete,
                    contentDescription = stringResource(R.string.common_delete),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        subscription.lastError?.takeIf { it.isNotBlank() }?.let { error ->
            Text(
                text = stringResource(R.string.subscription_last_error, error),
                modifier = Modifier.padding(top = 8.dp),
                fontSize = 12.sp,
                color = StatusColors.danger,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    WindowDialog(
        show = showDeleteDialog,
        title = stringResource(R.string.subscription_delete_title),
        summary = stringResource(R.string.subscription_delete_summary, displayName),
        onDismissRequest = { showDeleteDialog = false },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                text = stringResource(R.string.common_cancel),
                modifier = Modifier.weight(1f),
                onClick = { showDeleteDialog = false },
            )
            TextButton(
                text = stringResource(R.string.common_confirm),
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = {
                    showDeleteDialog = false
                    onDelete()
                },
            )
        }
    }
}

// 「添加配置」菜单项：图标 + 标题 + 选中后的动作，与原二级页的三类入口一一对应。
private data class AddMenuEntry(
    val title: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)
