package com.clashgo.android.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.clashgo.android.R
import com.clashgo.android.data.api.MihomoConnectionManager
import com.clashgo.android.platform.PlatformStorage
import com.clashgo.android.platform.StorageKeys
import com.clashgo.android.platform.TunMode
import com.clashgo.android.util.AppLogger
import com.clashgo.android.util.FormatUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

class DynamicNotificationManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val connectionManager: MihomoConnectionManager,
) {

    private var trafficJob: Job? = null
    private var screenReceiver: BroadcastReceiver? = null

    // 通知只有亮屏才看得见，灭屏后每秒一次的 WS 推帧加 notify() 刷新纯属空转：
    // 内核一次推送 -> 解析 JSON -> 重建通知视图 -> binder 跨进程，一天白烧数万次。
    // 灭屏即取消收集（WS 通道随之断开），通知停在该刻的数值；亮屏重新订阅。
    private val screenOn = MutableStateFlow(true)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(profileName: String) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)

        registerScreenReceiver()

        // 瞬时异常（比如系统偶发拒绝刷新通知）要在收集内部处理掉。给整条流挂捕获是终结型的，
        // 捕获之后流就结束、不会重订阅，曾经因此让流量通知永久停更。
        trafficJob?.cancel()
        trafficJob = scope.launch {
            screenOn.flatMapLatest { interactive ->
                if (interactive) {
                    connectionManager.repository
                        .filterNotNull()
                        .flatMapLatest { it.trafficFlow() }
                } else {
                    emptyFlow()
                }
            }.collect { traffic ->
                runCatching {
                    val notification = NotificationHelper.buildDynamicNotification(
                        context = context,
                        profileName = profileName,
                        uploadTotal = FormatUtils.formatBytes(traffic.upTotal),
                        downloadTotal = FormatUtils.formatBytes(traffic.downTotal),
                        uploadSpeed = FormatUtils.formatSpeed(traffic.up),
                        downloadSpeed = FormatUtils.formatSpeed(traffic.down),
                    )
                    notificationManager?.notify(NotificationHelper.NOTIFICATION_ID_VPN, notification)
                }.onFailure { AppLogger.warn(TAG, "Notify failed: $it") }
            }
        }
    }

    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                screenOn.value = intent.action == Intent.ACTION_SCREEN_ON
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        runCatching {
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            screenReceiver = receiver
            screenOn.value = context.getSystemService(PowerManager::class.java)?.isInteractive ?: true
        }.onFailure { AppLogger.warn(TAG, "Register screen receiver failed: $it") }
    }

    private fun unregisterScreenReceiver() {
        val receiver = screenReceiver ?: return
        runCatching { context.unregisterReceiver(receiver) }
            .onFailure { AppLogger.warn(TAG, "Unregister screen receiver failed: $it") }
        screenReceiver = null
    }

    // ROOT 模式一律用静态通知：应用进程没有 VPN 服务那层系统照顾，退到后台后系统会把每秒一次的
    // 流量推送和通知刷新合并掉，动态通知会卡住不动。
    fun startOrFallbackStatic(storage: PlatformStorage, tunMode: TunMode = TunMode.Vpn) {
        val isDynamicEnabled = storage.getString(StorageKeys.DYNAMIC_NOTIFICATION, "true") == "true"
        val isDynamic = isDynamicEnabled && tunMode == TunMode.Vpn
        if (isDynamic) {
            val profileName = storage.getString(StorageKeys.ACTIVE_PROFILE_NAME, "ClashGo")
            start(profileName)
        } else {
            val mode = context.getString(
                when (tunMode) {
                    TunMode.RootTun -> R.string.settings_tun_mode_root_tun
                    TunMode.RootTproxy -> R.string.settings_tun_mode_root_tproxy
                    TunMode.Vpn -> R.string.settings_tun_mode_vpn
                }
            )
            val notification = NotificationHelper.buildRunningNotification(context, mode)
            context.getSystemService(NotificationManager::class.java)
                ?.notify(NotificationHelper.NOTIFICATION_ID_VPN, notification)
        }
    }

    fun stop() {
        trafficJob?.cancel()
        trafficJob = null
        unregisterScreenReceiver()
    }

    companion object {
        private const val TAG = "DynamicNotification"
    }
}
