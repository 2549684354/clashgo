package com.clashgo.android.data.api

import com.clashgo.android.domain.repository.MihomoRepository
import com.clashgo.android.domain.repository.SubscriptionRepository
import com.clashgo.android.util.AppLogger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// 同 PC 的 SubscriptionAutoDelayPlanner：按当前订阅的 AutoTestDelayIntervalMinutes 测全部节点，
// 首轮在一个间隔之后；内核重启、切订阅或改间隔都重新计时。与 PC 不同，测完不解除固定选择。
// 测延迟只服务于界面展示（url-test 组的自动选择由内核自己做），因此整条循环以应用前台可见为闸：
// 退到后台即取消计时与在测的请求，回前台重新计时。
class AutoDelayTester(
    private val scope: CoroutineScope,
    private val connectionManager: MihomoConnectionManager,
    private val subscriptions: SubscriptionRepository,
    private val appVisible: StateFlow<Boolean>,
) {
    private val _completed = MutableSharedFlow<MihomoRepository>(extraBufferCapacity = 1)

    // 每轮测完发出所用的 client，消费方只在它仍是自己当前的 client 时刷新。
    val completed: SharedFlow<MihomoRepository> = _completed.asSharedFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        val schedule = combine(subscriptions.subscriptions, subscriptions.currentSubscriptionId) { items, id ->
            id to (items.firstOrNull { it.id == id }?.autoTestDelayIntervalMinutes ?: 0)
        }.distinctUntilChanged()
        scope.launch {
            combine(connectionManager.repository, schedule, appVisible) { repo, (id, interval), visible ->
                if (repo == null || id == null || interval <= 0 || !visible) null
                else TestTarget(repo, id, interval)
            }.collectLatest { target ->
                if (target == null) return@collectLatest
                while (true) {
                    delay(target.intervalMinutes.minutes)
                    try {
                        testAll(target.repo)
                        AppLogger.info(TAG, "Auto delay test finished for ${target.subscriptionId}")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLogger.warn(TAG, "Auto delay test failed for ${target.subscriptionId}", e)
                    }
                    _completed.tryEmit(target.repo)
                }
            }
        }
    }

    private data class TestTarget(
        val repo: MihomoRepository,
        val subscriptionId: String,
        val intervalMinutes: Int,
    )

    private suspend fun testAll(repo: MihomoRepository) {
        val groups = repo.getGroups().getOrThrow().proxies
        val runtime = repo.getProxies().getOrThrow().proxies
        val providerOf = HashMap<String, String>()
        repo.getProviders().getOrNull()?.providers?.forEach { (provider, detail) ->
            detail.proxies.forEach { node -> if (node.name !in runtime) providerOf.putIfAbsent(node.name, provider) }
        }
        repo.testDelays(groups.flatMapTo(LinkedHashSet()) { it.all }, providerOf)
    }

    private companion object {
        const val TAG = "AutoDelayTester"
    }
}
