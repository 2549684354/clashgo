package com.clashgo.android.data.store

import com.clashgo.android.platform.ProfileFileManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class ProxyDelayFile(
    // 订阅 id → 节点名 → 最近一次测得的延迟（-1 表示 timeout）
    @SerialName("Subscriptions") val subscriptions: Map<String, Map<String, Int>> = emptyMap(),
)

// 各订阅最近一次测得的节点延迟，跨进程重启保留：内核停止后 history 不可用，
// 代理页（预览态与刚启动的运行态）从这里回填，延迟与按延迟排序不因退出 APP 丢失。
class ProxyDelayStore(fileManager: ProfileFileManager, scope: CoroutineScope) {

    private val file = JsonFileStore(
        fileManager, DELAY_PATH, ProxyDelayFile.serializer(), ProxyDelayFile(), scope,
    )

    fun delays(subscriptionId: String): Map<String, Int> =
        file.value.subscriptions[subscriptionId].orEmpty()

    fun update(subscriptionId: String, node: String, delay: Int) {
        file.updateLater { state ->
            val nodes = state.subscriptions[subscriptionId].orEmpty() + (node to delay)
            state.copy(subscriptions = state.subscriptions + (subscriptionId to nodes))
        }
    }

    fun removeSubscription(subscriptionId: String) {
        file.updateLater { it.copy(subscriptions = it.subscriptions - subscriptionId) }
    }

    companion object {
        const val DELAY_PATH = "${ProxySelectionStore.DIRECTORY}/delay_state.json"
    }
}
