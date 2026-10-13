package com.clashgo.android.ui.screen.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clashgo.android.R
import com.clashgo.android.domain.model.ConfigurationOverride
import com.clashgo.android.ui.component.CardItem
import com.clashgo.android.ui.component.RestartRequiredHint
import com.clashgo.android.ui.component.TriStatePreference
import com.clashgo.android.ui.component.groupedCardItems
import com.clashgo.android.viewmodel.ClashFeaturesViewModel
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference

@Composable
fun NetworkSettingsScreen(
    viewModel: ClashFeaturesViewModel,
    onBack: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val editor = rememberOverrideEditorState()
    val sniffer = state.sniffer

    SettingsSubPage(title = stringResource(R.string.network_settings_title), onBack = onBack) {
        item { RestartRequiredHint() }

        item { SmallTitle(text = stringResource(R.string.network_options)) }
        groupedCardItems(
            keyPrefix = "network_options",
            items = listOf(
                CardItem("delayTestUrl") {
                    val title = stringResource(R.string.network_delay_test_url)
                    val hint = stringResource(R.string.network_delay_test_url_hint)
                    ArrowPreference(
                        title = title,
                        // 未修改时只显示「不修改」，默认 URL 保留在编辑框里。
                        summary = state.delayTestUrl ?: stringResource(R.string.common_not_modified),
                        onClick = {
                            editor.editText(
                                title,
                                state.delayTestUrl ?: ConfigurationOverride.DEFAULT_DELAY_TEST_URL,
                                hint,
                            ) { v ->
                                viewModel.update { it.copy(delayTestUrl = v?.trim()?.takeIf { url -> url.isNotEmpty() }) }
                            }
                        },
                    )
                },
                CardItem("unifiedDelay") {
                    TriStatePreference(
                        title = stringResource(R.string.network_unified_delay),
                        value = state.unifiedDelay,
                        onValueChange = { v -> viewModel.update { it.copy(unifiedDelay = v) } },
                    )
                },
                CardItem("allowLan") {
                    TriStatePreference(
                        title = stringResource(R.string.network_allow_lan),
                        value = state.allowLan,
                        onValueChange = { v -> viewModel.update { it.copy(allowLan = v) } },
                    )
                },
                CardItem("ipv6") {
                    TriStatePreference(
                        title = stringResource(R.string.network_ipv6),
                        value = state.ipv6,
                        onValueChange = { v -> viewModel.update { it.copy(ipv6 = v) } },
                    )
                },
                CardItem("tcpConcurrent") {
                    TriStatePreference(
                        title = stringResource(R.string.network_tcp_concurrent),
                        value = state.tcpConcurrent,
                        onValueChange = { v -> viewModel.update { it.copy(tcpConcurrent = v) } },
                    )
                },
                CardItem("bindAddress") {
                    val title = stringResource(R.string.network_bind_address)
                    ArrowPreference(
                        title = title,
                        summary = state.bindAddress ?: stringResource(R.string.common_not_modified),
                        onClick = {
                            editor.editText(title, state.bindAddress) { v -> viewModel.update { it.copy(bindAddress = v) } }
                        },
                    )
                },
            ),
        )

        item { SmallTitle(text = stringResource(R.string.network_sniffer)) }
        groupedCardItems(
            keyPrefix = "network_sniffer",
            items = listOf(
                CardItem("enable") {
                    TriStatePreference(
                        title = stringResource(R.string.network_sniffer_enable),
                        value = sniffer?.enable,
                        onValueChange = { v -> viewModel.updateSniffer { it.copy(enable = v) } },
                    )
                },
                CardItem("forceDnsMapping") {
                    TriStatePreference(
                        title = stringResource(R.string.network_sniffer_force_dns_mapping),
                        value = sniffer?.forceDnsMapping,
                        onValueChange = { v -> viewModel.updateSniffer { it.copy(forceDnsMapping = v) } },
                    )
                },
                CardItem("parsePureIp") {
                    TriStatePreference(
                        title = stringResource(R.string.network_sniffer_parse_pure_ip),
                        value = sniffer?.parsePureIp,
                        onValueChange = { v -> viewModel.updateSniffer { it.copy(parsePureIp = v) } },
                    )
                },
                CardItem("overrideDest") {
                    TriStatePreference(
                        title = stringResource(R.string.network_sniffer_override_dest),
                        value = sniffer?.overrideDestination,
                        onValueChange = { v -> viewModel.updateSniffer { it.copy(overrideDestination = v) } },
                    )
                },
                CardItem("forceDomain") {
                    val title = stringResource(R.string.network_sniffer_force_domain)
                    ArrowPreference(
                        title = title,
                        summary = listSummary(sniffer?.forceDomain),
                        onClick = {
                            editor.editList(title, sniffer?.forceDomain) { v ->
                                viewModel.updateSniffer { it.copy(forceDomain = v) }
                            }
                        },
                    )
                },
                CardItem("skipDomain") {
                    val title = stringResource(R.string.network_sniffer_skip_domain)
                    ArrowPreference(
                        title = title,
                        summary = listSummary(sniffer?.skipDomain),
                        onClick = {
                            editor.editList(title, sniffer?.skipDomain) { v ->
                                viewModel.updateSniffer { it.copy(skipDomain = v) }
                            }
                        },
                    )
                },
            ),
        )
    }

    OverrideEditorDialogs(editor)
}
