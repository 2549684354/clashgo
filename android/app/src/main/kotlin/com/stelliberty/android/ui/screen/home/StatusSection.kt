package com.stelliberty.android.ui.screen.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stelliberty.android.R
import com.stelliberty.android.ui.icon.AppIcons
import com.stelliberty.android.ui.theme.RunState
import com.stelliberty.android.ui.theme.StatusColors
import com.stelliberty.android.ui.util.TestTags
import com.stelliberty.android.ui.util.label
import com.stelliberty.android.viewmodel.HomeUiState
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonColors
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme

fun LazyListScope.statusSection(
    state: HomeUiState = HomeUiState(),
    uptime: String = "00:00:00",
    onStop: () -> Unit = {},
    onStart: () -> Unit = {},
) {
    item(key = "status") {
        StatusContent(state, uptime, onStop, onStart)
    }
}

@Composable
private fun StatusContent(
    state: HomeUiState,
    uptime: String,
    onStop: () -> Unit,
    onStart: () -> Unit,
) {
    val isRunning = state.isRunning
    val isStarting = state.isStarting
    val isStopping = state.isStopping

    val runState = when {
        isStarting || isStopping -> RunState.Pending
        isRunning -> RunState.Running
        else -> RunState.Stopped
    }
    val statusTint = StatusColors.runState(runState)

    val mixedPort = state.config?.mixedPort ?: 0
    val tproxyPort = state.config?.tproxyPort ?: 0
    val mixedLabel = if (mixedPort > 0) stringResource(R.string.home_port_mixed, mixedPort) else ""
    val tproxyLabel = if (tproxyPort > 0) stringResource(R.string.home_port_tproxy, tproxyPort) else ""
    val portsText = listOf(mixedLabel, tproxyLabel).filter { it.isNotEmpty() }.joinToString(" · ")
    val coreCaption = if (state.version.isEmpty()) CORE_NAME else "$CORE_NAME ${state.version}"
    val caption = listOf(coreCaption, portsText).filter { it.isNotEmpty() }.joinToString(" · ")

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 12.dp, bottom = 6.dp)
            .cardShadow(HomeCardCornerRadius)
            .testTag(TestTags.Home.STATUS_CARD),
        insideMargin = PaddingValues(20.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).squircleBackground(statusTint, 4.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    text = when {
                        isStopping -> stringResource(R.string.home_stopping)
                        isStarting -> stringResource(R.string.home_starting)
                        isRunning -> stringResource(R.string.home_running)
                        else -> stringResource(R.string.home_stopped)
                    },
                    modifier = Modifier.weight(1f),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Text(
                    text = state.tunMode.label(),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                StatusDetailLine(stringResource(R.string.home_uptime), Modifier.weight(1f))
                StatusDetailLine(stringResource(R.string.home_active_profile), Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text(
                    text = if (isRunning) uptime else "00:00:00",
                    modifier = Modifier.weight(1f).alignByBaseline(),
                    style = StatusValueStyle,
                    autoSize = TextAutoSize.StepBased(minFontSize = 18.sp, maxFontSize = 20.sp, stepSize = 1.sp),
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Text(
                    text = state.profileName.ifEmpty { NO_VALUE },
                    modifier = Modifier.weight(1f).alignByBaseline(),
                    style = StatusValueStyle,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(20.dp))
            StatusActionArea(
                state = state,
                onStart = onStart,
                onStop = onStop,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = caption,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = StatusDetailStyle,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// 操作区随状态切换：停止态是主色大胶囊「启动」，运行态是与启动同尺寸的动作色大胶囊「停止」。
// 过渡期（启动中/停止中）不显示任何加载动画：按钮维持原样、点击暂时不响应，状态就绪后自动切换。
// 热重载在主页「工具」区，重启由「停止 → 启动」覆盖，不再单独占位。
@Composable
private fun StatusActionArea(
    state: HomeUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    when {
        state.isStarting -> StartButton(onStart = {})
        state.isStopping -> StopButton(onStop = {})
        state.isRunning -> StopButton(onStop)
        else -> StartButton(onStart)
    }
}

@Composable
private fun StartButton(onStart: () -> Unit) {
    GelButton(
        onClick = onStart,
        modifier = Modifier.fillMaxWidth(),
        tag = TestTags.Home.START,
        containerColor = MiuixTheme.colorScheme.primary,
        disabledContainerColor = MiuixTheme.colorScheme.primary,
        cornerRadius = ActionButtonRadius,
        minHeight = ActionButtonHeight,
    ) {
        Icon(
            imageVector = AppIcons.Play,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.home_start),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun StopButton(onStop: () -> Unit) {
    GelButton(
        onClick = onStop,
        modifier = Modifier.fillMaxWidth(),
        tag = TestTags.Home.STOP,
        containerColor = StatusColors.stopAction,
        disabledContainerColor = StatusColors.stopAction,
        cornerRadius = ActionButtonRadius,
        minHeight = ActionButtonHeight,
    ) {
        Icon(
            imageVector = AppIcons.Stop,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.home_stop),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// 果冻质感胶囊：Miuix Button 只支持实色容器，这里保留它做底（点击/按压/禁用语义不变），
// 在上面叠一层非交互的纵向高光——顶部提亮、中段消退、底部轻微加深，画出参考图的立体渐变。
// 覆盖层只有绘制没有手势，触摸事件全部落到下层 Button。
@Composable
private fun GelButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    // 过渡期（启动中/停止中）的设计是按钮维持原样、点击不响应，因此当前调用点都不传 enabled=false；
    // enabled 与两个 disabled 颜色参数是为将来需要真正禁用态（变灰不可点）预留的，先保留。
    enabled: Boolean = true,
    containerColor: Color,
    disabledContainerColor: Color,
    cornerRadius: Dp,
    minHeight: Dp,
    tag: String? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Box(modifier) {
        // 按钮负责撑满容器宽度并撑起自身高度（容器宽度由 fillMaxWidth/Row weight 决定），
        // Box 才有确定尺寸，高光的 matchParentSize 才能贴准按钮。
        Button(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (tag != null) Modifier.testTag(tag) else Modifier),
            enabled = enabled,
            minHeight = minHeight,
            cornerRadius = cornerRadius,
            colors = ButtonColors(
                color = containerColor,
                disabledColor = disabledContainerColor,
                contentColor = Color.White,
                disabledContentColor = Color.White.copy(alpha = DisabledContentAlpha),
            ),
            content = content,
        )
    }
}

@Composable
private fun StatusDetailLine(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        fontSize = 12.sp,
        letterSpacing = 0.sp,
        style = StatusDetailStyle,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private const val CORE_NAME = "Mihomo"

private const val NO_VALUE = "--"

private const val DisabledContentAlpha = 0.7f

// 启动/停止共用同一套尺寸：全宽胶囊，高度收在 46dp，圆角取半径 = 高度一半保持胶囊形。
private val ActionButtonHeight = 46.dp

private val ActionButtonRadius = 23.dp

private val StatusValueStyle = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontSize = 20.sp,
    lineHeight = 28.sp,
    fontWeight = FontWeight.Medium,
    fontFeatureSettings = "tnum",
    letterSpacing = 0.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
)

private val StatusDetailStyle = StatusValueStyle.copy(
    fontSize = 12.sp,
    lineHeight = 18.sp,
    fontWeight = FontWeight.Normal,
)
