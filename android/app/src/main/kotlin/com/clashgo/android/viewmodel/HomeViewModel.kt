package com.clashgo.android.viewmodel

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clashgo.android.BuildConfig
import com.clashgo.android.R
import com.clashgo.android.data.api.MihomoConnectionManager
import com.clashgo.android.data.api.RuleLatencyTester
import com.clashgo.android.data.repository.OverrideJsonStore
import com.clashgo.android.domain.model.ConnectionInfo
import com.clashgo.android.domain.model.MihomoConfig
import com.clashgo.android.domain.model.ProvidersResponse
import com.clashgo.android.domain.model.Subscription
import com.clashgo.android.domain.model.SubscriptionInfo
import com.clashgo.android.domain.model.TunOverride
import com.clashgo.android.domain.repository.MihomoRepository
import com.clashgo.android.platform.PlatformSystemInfo
import com.clashgo.android.platform.ProxyServiceController
import com.clashgo.android.platform.ProxyState
import com.clashgo.android.platform.TunMode
import com.clashgo.android.platform.showToast
import com.clashgo.android.util.FormatUtils
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
data class HomeUiState(
    val isRunning: Boolean = false,
    val isStarting: Boolean = false,
    val isStopping: Boolean = false,
    val mode: String = "--",
    val tunStack: String = "",
    val tunMode: TunMode = TunMode.Vpn,
    val ipv6: Boolean = false,
    val config: MihomoConfig? = null,
    val subscription: SubscriptionInfo? = null,
    val providerTraffic: ImmutableList<ProviderTrafficInfo> = persistentListOf(),
    val isProviderTrafficLoading: Boolean = false,
    val providerTrafficLoadFailed: Boolean = false,
    val latencyBaidu: Int = -1,
    val latencyCloudflare: Int = -1,
    val latencyGoogle: Int = -1,
    val isTestingLatency: Boolean = false,
    val latencyViaRules: Boolean = true,
    val exitIp: String = "",
    val exitRegion: String = "",
    val version: String = "",
    val profileName: String = "",
    val errorMessage: String = "",
    val needsVpnPermission: Boolean = false,
)

@Immutable
data class ProviderTrafficInfo(
    val id: String,
    val name: String,
    val nodeCount: Int,
    val updatedAt: String,
    val hasTraffic: Boolean,
    val upload: Long,
    val download: Long,
    val total: Long,
    val expire: Long,
)

private const val VEHICLE_TYPE_COMPATIBLE = "Compatible"

@Immutable
data class TrafficHistory(
    val up: ImmutableList<Long> = persistentListOf(),
    val down: ImmutableList<Long> = persistentListOf(),
    val seq: Int = 0,
)

@Immutable
data class SpeedSnapshot(
    val uploadSpeed: String = "-- B/s",
    val downloadSpeed: String = "-- B/s",
    val history: TrafficHistory = TrafficHistory(),
)

// 延迟探测一律用 204 空响应端点：完整页面（如 www.cloudflare.com/cdn-cgi/trace）的
// 传输与解析都会把数字抬高，204 只衡量往返耗时，站点之间才有可比性。
enum class LatencyProbe(val url: String) {
    Baidu("http://www.baidu.com"),
    Cloudflare("http://cp.cloudflare.com/generate_204"),
    Google("http://www.google.com/generate_204"),
}

@Immutable
data class MemorySnapshot(
    val ramUsage: String = "-- MB",
    val ramTotal: String = "-- MB",
)

@Immutable
data class ConnectionRate(
    val id: String,
    val host: String,
    val node: String,
    val uploadRate: Long,
    val downloadRate: Long,
)

@Immutable
data class SystemInfoSnapshot(
    val localIp: String = "0.0.0.0",
    val interfaceName: String = "--",
    val cpuUsage: String = "--%",
)

class HomeViewModel(
    private val context: Context,
    private val serviceController: ProxyServiceController,
    private val overrideStore: OverrideJsonStore,
    private val connectionManager: MihomoConnectionManager,
    private val latencyTester: RuleLatencyTester,
    private val appVisible: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow(),
    private val nodeSwitches: Flow<Unit> = emptyFlow(),
    private val getActiveSubscriptionId: () -> String? = { null },
    private val activeSubscription: StateFlow<Subscription?> = MutableStateFlow(null).asStateFlow(),
    private val onLiveProviderInfo: (subscriptionId: String?, info: SubscriptionInfo?) -> Unit = { _, _ -> },
) : ViewModel() {

    // 内核版本在构建期与 libmihomo 一同注入（ldflags 同源），启动前就有值；
    // 内核起来后 /version 的返回会再回填一次，两处一致。
    private val _uiState = MutableStateFlow(HomeUiState(mode = persistedMode(), version = BuildConfig.MIHOMO_VERSION))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _speedState = MutableStateFlow(SpeedSnapshot())
    val speedState: StateFlow<SpeedSnapshot> = _speedState.asStateFlow()

    private val _memoryState = MutableStateFlow(MemorySnapshot())
    val memoryState: StateFlow<MemorySnapshot> = _memoryState.asStateFlow()

    private val _systemInfoState = MutableStateFlow(SystemInfoSnapshot())
    val systemInfoState: StateFlow<SystemInfoSnapshot> = _systemInfoState.asStateFlow()

    private val _uptimeState = MutableStateFlow(-1L)
    val uptimeState: StateFlow<Long> = _uptimeState.asStateFlow()

    private val upHistory = ArrayDeque<Long>(TRAFFIC_HISTORY_CAPACITY)
    private val downHistory = ArrayDeque<Long>(TRAFFIC_HISTORY_CAPACITY)
    private var trafficSeq = 0

    private val _topConnectionRates = MutableStateFlow<ImmutableList<ConnectionRate>?>(null)
    val topConnectionRates: StateFlow<ImmutableList<ConnectionRate>?> = _topConnectionRates.asStateFlow()

    private var prevConnectionBytes = emptyMap<String, LongArray>()
    private var prevConnectionAtMillis = 0L

    private var repository: MihomoRepository? = null
    private var latencyJob: Job? = null
    private var latencyGeneration = 0
    private var connectionRateJob: Job? = null
    private var trafficJob: Job? = null
    private var memoryJob: Job? = null
    private var systemInfoJob: Job? = null
    private var runtimeConfigJob: Job? = null
    private var initialLoadJob: Job? = null
    private var providerTrafficJob: Job? = null
    private var providerTrafficRequestId = 0L
    private var repositorySubscriptionId: String? = null
    private var startTime: Long = 0
    private var uptimeJob: Job? = null
    private var mihomoPid: Int = -1
    private val systemInfo = PlatformSystemInfo()

    // 可见性是两个信号的与：Activity 前后台（appVisible 即共享的 AppVisibilityMonitor，
    // MainActivity onStart/onStop 写入）+ 首页分页是否真的可见（主页面常驻组合，
    // Pager 包装的生命周期才反映分页离屏）。任一为假都按不可见处理。
    private val homeVisible = MutableStateFlow(true)
    private val uiVisible = combine(appVisible, homeVisible) { foreground, home -> foreground && home }

    private var lastErrorToast: String? = null

    init {
        viewModelScope.launch {
            serviceController.status.collect { status ->
                when (status.state) {
                    ProxyState.Starting -> {
                        _uiState.value = _uiState.value.copy(
                            isStarting = true,
                            isStopping = false,
                            tunMode = status.tunMode,
                            subscription = activeSubscriptionInfo(),
                        )
                    }

                    ProxyState.Running -> {
                        _uiState.value = _uiState.value.copy(
                            isStarting = false,
                            isRunning = true,
                            tunMode = status.tunMode,
                            subscription = activeSubscriptionInfo(),
                        )
                        startTime = if (status.startTime > 0) status.startTime else Clock.System.now().toEpochMilliseconds()
                        mihomoPid = status.mihomoPid
                    }

                    ProxyState.Stopping -> {
                        _uiState.value = _uiState.value.copy(isRunning = false, isStopping = true)
                    }

                    // 这两个分支整体重建状态来清掉运行期的热数据，因此每个要留下的字段都得显式带上。
                    // tunMode 漏了就退回构造默认的 VPN，用户选着 ROOT 却在首页看到 VPN；
                    // version 漏了标题下的内核版本就会在停止后消失（构建期注入的值不会变）。
                    ProxyState.Stopped -> {
                        _uiState.value = HomeUiState(
                            mode = persistedMode(),
                            profileName = _uiState.value.profileName,
                            tunMode = status.tunMode,
                            version = _uiState.value.version,
                        )
                        lastErrorToast = null
                        resetHotStates()
                    }

                    ProxyState.Error -> {
                        val message = status.errorMessage
                        if (message.isNotBlank() && !status.errorNotified && message != lastErrorToast) {
                            lastErrorToast = message
                            showToast(message, long = true)
                        }
                        _uiState.value = HomeUiState(
                            errorMessage = message,
                            mode = persistedMode(),
                            profileName = _uiState.value.profileName,
                            tunMode = status.tunMode,
                            version = _uiState.value.version,
                        )
                        resetHotStates()
                    }
                }
            }
        }
        viewModelScope.launch {
            connectionManager.repository.collect { repo ->
                repository = repo
                if (repo != null) {
                    // 速度详情打开期间内核重启：旧收集任务绑在被关闭的旧客户端上，
                    // 必须用新仓库重启，否则留下僵尸重试流且新速率数据永远不来。
                    val rateTrackingActive = connectionRateJob?.isActive == true
                    connectToMihomo()
                    if (rateTrackingActive) startConnectionRateTracking()
                } else {
                    disconnectStreams()
                }
            }
        }
        viewModelScope.launch {
            activeSubscription.collect { sub ->
                _uiState.value = _uiState.value.copy(profileName = sub?.name.orEmpty())
                if (_uiState.value.isRunning || _uiState.value.isStarting) {
                    _uiState.value = _uiState.value.copy(subscription = activeSubscriptionInfo())
                }
                if (repository != null && getActiveSubscriptionId() != repositorySubscriptionId) {
                    clearProviderTraffic()
                }
            }
        }
        // 节点切换后实际出口变了，重测一次站点访问延迟；仅此与内核连接（启动/重启）两处自动触发。
        viewModelScope.launch {
            nodeSwitches.collect {
                if (_uiState.value.isRunning) testLatency(force = true)
            }
        }
    }

    private fun connectToMihomo() {
        val repo = repository ?: return
        val subscriptionId = getActiveSubscriptionId()
        repositorySubscriptionId = subscriptionId
        _uiState.value = _uiState.value.copy(subscription = activeSubscriptionInfo())
        startTrafficCollection()
        startMemoryCollection()
        startSystemInfoCollection()
        startRuntimeConfigRefresh()
        startUptimeCounter()
        initialLoadJob?.cancel()
        initialLoadJob = viewModelScope.launch {
            loadConfig(repo)
            if (repository === repo) testLatency()
        }
        refreshProviderTraffic(repo, subscriptionId)
    }

    private suspend fun loadConfig(repo: MihomoRepository) {
        repo.getConfig().onSuccess { config ->
            if (repository !== repo) return@onSuccess
            _uiState.update {
                it.copy(
                    isRunning = true,
                    mode = config.mode,
                    tunStack = config.tun?.stack ?: "",
                    ipv6 = config.ipv6,
                    config = config,
                )
            }
        }
        repo.getVersion().onSuccess { version ->
            if (repository !== repo) return@onSuccess
            _uiState.update { it.copy(version = version.version) }
        }
    }

    fun refreshProviderTraffic() {
        val repo = repository ?: return
        val subscriptionId = repositorySubscriptionId
        if (getActiveSubscriptionId() != subscriptionId) return
        refreshProviderTraffic(repo, subscriptionId)
    }

    private fun refreshProviderTraffic(repo: MihomoRepository, subscriptionId: String?) {
        providerTrafficJob?.cancel()
        val requestId = ++providerTrafficRequestId
        setProviderTrafficLoading(repo, subscriptionId, requestId)
        providerTrafficJob = viewModelScope.launch {
            loadProviderTrafficSnapshot(repo, subscriptionId, requestId)
        }
    }

    // mihomo 只在重新拉取订阅源时才刷新用量信息，光查询读到的永远是上次的旧数据。
    // 所以逐个触发更新（单个失败不影响其他），全部完成后再统一读一遍。
    fun updateAllProviders() {
        val repo = repository ?: return
        val subscriptionId = repositorySubscriptionId
        if (getActiveSubscriptionId() != subscriptionId) return
        providerTrafficJob?.cancel()
        val requestId = ++providerTrafficRequestId
        setProviderTrafficLoading(repo, subscriptionId, requestId)
        providerTrafficJob = viewModelScope.launch {
            val names = repo.getProviders().getOrNull()?.providers.orEmpty()
                .filterValues { !it.vehicleType.equals(VEHICLE_TYPE_COMPATIBLE, ignoreCase = true) }
                .map { (fallbackName, provider) -> provider.name.ifBlank { fallbackName } }
            if (!isCurrentProviderTrafficRequest(repo, subscriptionId, requestId)) return@launch
            names.map { name -> async { repo.updateProvider(name) } }.awaitAll()
            if (!isCurrentProviderTrafficRequest(repo, subscriptionId, requestId)) return@launch
            loadProviderTrafficSnapshot(repo, subscriptionId, requestId)
        }
    }

    private fun setProviderTrafficLoading(repo: MihomoRepository, subscriptionId: String?, requestId: Long) {
        _uiState.update { state ->
            if (!isCurrentProviderTrafficRequest(repo, subscriptionId, requestId)) state else state.copy(
                isProviderTrafficLoading = true,
                providerTrafficLoadFailed = false,
            )
        }
    }

    private suspend fun loadProviderTrafficSnapshot(
        repo: MihomoRepository,
        subscriptionId: String?,
        requestId: Long,
    ) {
        val result = repo.getProviders()
        if (!isCurrentProviderTrafficRequest(repo, subscriptionId, requestId)) return
        result.onSuccess { providers ->
            _uiState.update { state ->
                if (!isCurrentProviderTrafficRequest(repo, subscriptionId, requestId)) state else state.copy(
                    providerTraffic = providerTrafficInfo(providers),
                    isProviderTrafficLoading = false,
                    providerTrafficLoadFailed = false,
                )
            }
            if (isCurrentProviderTrafficRequest(repo, subscriptionId, requestId)) {
                onLiveProviderInfo(subscriptionId, aggregateProviderInfo(providers))
            }
        }.onFailure {
            _uiState.update { state ->
                if (!isCurrentProviderTrafficRequest(repo, subscriptionId, requestId)) state else state.copy(
                    isProviderTrafficLoading = false,
                    providerTrafficLoadFailed = true,
                )
            }
        }
    }

    private fun isCurrentProviderTrafficRequest(
        repo: MihomoRepository,
        subscriptionId: String?,
        requestId: Long,
    ): Boolean = repository === repo &&
            repositorySubscriptionId == subscriptionId &&
            getActiveSubscriptionId() == subscriptionId &&
            providerTrafficRequestId == requestId

    private fun clearProviderTraffic() {
        providerTrafficJob?.cancel()
        providerTrafficRequestId++
        _uiState.update {
            it.copy(
                providerTraffic = persistentListOf(),
                isProviderTrafficLoading = false,
                providerTrafficLoadFailed = false,
            )
        }
    }

    // 必须把所有订阅源的用量加起来。用量信息是每个源各自解析响应头得来的，
    // 一份配置里有多个源时随便取第一个，拿到的是哪个全看遍历顺序。
    private fun aggregateProviderInfo(providers: ProvidersResponse): SubscriptionInfo? {
        val valid = providers.providers.values.mapNotNull { info ->
            info.subscriptionInfo?.takeIf { it.Total > 0 }
        }
        if (valid.isEmpty()) return null
        if (valid.size == 1) return valid.first()
        return SubscriptionInfo(
            Upload = valid.sumOf { it.Upload },
            Download = valid.sumOf { it.Download },
            Total = valid.sumOf { it.Total },
            Expire = valid.filter { it.Expire > 0 }.minOfOrNull { it.Expire } ?: 0,
        )
    }

    private fun providerTrafficInfo(providers: ProvidersResponse): ImmutableList<ProviderTrafficInfo> {
        return providers.providers
            .mapNotNull { (fallbackName, provider) ->
                if (provider.vehicleType.equals(VEHICLE_TYPE_COMPATIBLE, ignoreCase = true)) return@mapNotNull null
                val info = provider.subscriptionInfo
                ProviderTrafficInfo(
                    id = fallbackName,
                    name = provider.name.ifBlank { fallbackName },
                    nodeCount = provider.proxies.size,
                    updatedAt = provider.updatedAt,
                    hasTraffic = info != null,
                    upload = info?.Upload ?: 0,
                    download = info?.Download ?: 0,
                    total = info?.Total ?: 0,
                    expire = info?.Expire ?: 0,
                )
            }
            .sortedBy { it.name.lowercase() }
            .toPersistentList()
    }

    // 总量小于等于 0 时（不限量套餐，或者服务端根本没给总量）配额的概念不成立，
    // 返回空让界面显示两个横线、不画用量条。各处卡片一律以此为准，别再各写一份判断。
    private fun activeSubscriptionInfo(): SubscriptionInfo? {
        val traffic = activeSubscription.value?.trafficInfo ?: return null
        if (traffic.total <= 0) return null
        return SubscriptionInfo(
            Upload = traffic.upload,
            Download = traffic.download,
            Total = traffic.total,
            Expire = traffic.expire,
        )
    }

    private fun startTrafficCollection() {
        trafficJob?.cancel()
        trafficJob = collectWhileVisible {
            primeTrafficHistory()
            repository?.trafficFlow()
                ?.collect { traffic ->
                    upHistory.addLast(traffic.up)
                    downHistory.addLast(traffic.down)
                    while (upHistory.size > TRAFFIC_HISTORY_CAPACITY) upHistory.removeFirst()
                    while (downHistory.size > TRAFFIC_HISTORY_CAPACITY) downHistory.removeFirst()
                    _speedState.value = SpeedSnapshot(
                        uploadSpeed = FormatUtils.formatSpeed(traffic.up),
                        downloadSpeed = FormatUtils.formatSpeed(traffic.down),
                        history = TrafficHistory(
                            up = upHistory.toPersistentList(),
                            down = downHistory.toPersistentList(),
                            seq = ++trafficSeq,
                        ),
                    )
                    if (!_uiState.value.isRunning) {
                        _uiState.value = _uiState.value.copy(isRunning = true)
                    }
                }
        }
    }

    // 只在速度详情打开时才订阅：这条流每秒推送全部连接的完整列表，几百条常驻解析代价不小。
    // 仓库替换（内核重启）时也会再次调用：旧任务绑在已关闭的旧客户端上，必须取消换新。
    fun startConnectionRateTracking() {
        val repo = repository ?: return
        connectionRateJob?.cancel()
        connectionRateJob = null
        prevConnectionBytes = emptyMap()
        prevConnectionAtMillis = 0L
        _topConnectionRates.value = null
        connectionRateJob = viewModelScope.launch {
            repo.connectionsFlow()
                .collect { response ->
                    if (repository !== repo) return@collect
                    updateConnectionRates(response.connections)
                }
        }
    }

    fun stopConnectionRateTracking() {
        connectionRateJob?.cancel()
        connectionRateJob = null
        prevConnectionBytes = emptyMap()
        prevConnectionAtMillis = 0L
        _topConnectionRates.value = null
    }

    // mihomo 每条连接只给累计字节数，不给速度，照累计量排序会让长连接永远压在最前面。
    // 所以拿前后两次快照相减再除以实际间隔。第一轮只留基准不出结果，否则会把历史总量当成瞬时速度。
    private fun updateConnectionRates(connections: List<ConnectionInfo>) {
        val now = Clock.System.now().toEpochMilliseconds()
        val snapshot = connections.associate { it.id to longArrayOf(it.upload, it.download) }
        val elapsedMillis = now - prevConnectionAtMillis
        if (prevConnectionAtMillis == 0L || elapsedMillis <= 0) {
            prevConnectionBytes = snapshot
            prevConnectionAtMillis = now
            return
        }

        val perSecond = elapsedMillis / 1000.0
        val rates = connections.mapNotNull { conn ->
            val prev = prevConnectionBytes[conn.id]
            val upDelta = (conn.upload - (prev?.get(0) ?: 0L)).coerceAtLeast(0L)
            val downDelta = (conn.download - (prev?.get(1) ?: 0L)).coerceAtLeast(0L)
            if (upDelta == 0L && downDelta == 0L) return@mapNotNull null
            ConnectionRate(
                id = conn.id,
                host = conn.metadata.host.ifEmpty {
                    "${conn.metadata.destinationIP}:${conn.metadata.destinationPort}"
                },
                node = conn.chains.firstOrNull().orEmpty(),
                uploadRate = (upDelta / perSecond).toLong(),
                downloadRate = (downDelta / perSecond).toLong(),
            )
        }

        prevConnectionBytes = snapshot
        prevConnectionAtMillis = now
        _topConnectionRates.value = rates
            .sortedByDescending { it.uploadRate + it.downloadRate }
            .take(TOP_CONNECTION_COUNT)
            .toPersistentList()
    }

    private fun startMemoryCollection() {
        memoryJob?.cancel()
        memoryJob = collectWhileVisible {
            repository?.memoryFlow()
                ?.collect { memory ->
                    _memoryState.value = MemorySnapshot(
                        ramUsage = FormatUtils.formatBytes(memory.inuse),
                        ramTotal = if (memory.oslimit > 0) FormatUtils.formatBytes(memory.oslimit) else "-- MB",
                    )
                }
        }
    }

    // 这些轮询挂在 ViewModel 的作用域上，不感知界面是否可见。不按可见性掐掉，
    // 就是在后台或别的分页上每天几万次读系统文件和打本地请求。
    private fun pollWhileVisible(interval: Duration, block: suspend () -> Unit): Job =
        viewModelScope.launch {
            while (true) {
                uiVisible.first { it }
                block()
                delay(interval)
            }
        }

    fun setHomeVisible(visible: Boolean) {
        homeVisible.value = visible
    }

    // 与 pollWhileVisible 同一可见性契约，但用于不会自己结束的推送流：隐藏时取消收集协程，
    // WS 通道随之断开，不再有每秒一帧的推送唤醒；回前台重新订阅，流量窗口重新补零。
    private fun collectWhileVisible(block: suspend () -> Unit): Job = viewModelScope.launch {
        while (true) {
            uiVisible.first { it }
            val inner = launch { block() }
            uiVisible.first { !it }
            inner.cancel()
        }
    }

    private fun startUptimeCounter() {
        uptimeJob?.cancel()
        uptimeJob = pollWhileVisible(1000.milliseconds) {
            _uptimeState.value = (Clock.System.now().toEpochMilliseconds() - startTime) / 1000
        }
    }

    private fun startSystemInfoCollection() {
        systemInfoJob?.cancel()
        systemInfoJob = pollWhileVisible(2000.milliseconds) {
            val snapshot = withContext(Dispatchers.IO) {
                val networkInfo = systemInfo.getNetworkInfo()
                val cpu = systemInfo.getCpuUsage(mihomoPid)
                SystemInfoSnapshot(
                    localIp = networkInfo.localIp,
                    interfaceName = networkInfo.interfaceName,
                    cpuUsage = if (cpu >= 0) "${cpu.toInt()}%" else "--%",
                )
            }
            _systemInfoState.value = snapshot
        }
    }

    private fun startRuntimeConfigRefresh() {
        runtimeConfigJob?.cancel()
        runtimeConfigJob = pollWhileVisible(2000.milliseconds) { refreshRuntimeConfig() }
    }

    private suspend fun refreshRuntimeConfig() {
        repository?.getConfig()?.onSuccess { config ->
            val current = _uiState.value
            if (!current.isRunning || current.config == config) return@onSuccess
            _uiState.value = current.copy(
                mode = config.mode,
                tunStack = config.tun?.stack ?: "",
                ipv6 = config.ipv6,
                config = config,
            )
        }
    }

    // 先用 0 填满窗口：横向间距按窗口容量算，点数不够时曲线只占右边一小条（每秒一点即头
    // 一分钟都这样）。没流量时填 0 也是如实反映。
    private fun primeTrafficHistory() {
        upHistory.clear()
        downHistory.clear()
        repeat(TRAFFIC_HISTORY_CAPACITY) {
            upHistory.addLast(0L)
            downHistory.addLast(0L)
        }
    }

    private fun resetHotStates() {
        upHistory.clear()
        downHistory.clear()
        trafficSeq = 0
        _speedState.value = SpeedSnapshot()
        _memoryState.value = MemorySnapshot()
        _systemInfoState.value = SystemInfoSnapshot()
        _uptimeState.value = -1L
    }

    fun startProxy() {
        // 没有激活订阅时内核没有可用配置，启动必然失败；直接拦下并提醒。
        if (getActiveSubscriptionId() == null) {
            showToast(context.getString(R.string.home_no_subscription), long = true)
            return
        }
        if (!serviceController.hasVpnPermission()) {
            _uiState.value = _uiState.value.copy(needsVpnPermission = true)
            serviceController.requestVpnPermission()
            return
        }
        serviceController.start(getActiveSubscriptionId())
    }

    fun stopProxy() {
        serviceController.stop()
    }

    fun onActiveSubscriptionChanged() {
        serviceController.onActiveSubscriptionChanged()
    }

    private fun persistedMode(): String = overrideStore.load().mode?.takeIf { it.isNotBlank() } ?: "--"

    // 停止状态下也能改：写设置和代理是否在跑没关系，重启的事交给控制器判断。
    // 别改成直接重启，那会把用户没打算启动的代理拉起来。
    fun switchMode(mode: String) {
        val current = overrideStore.load()
        overrideStore.save(current.copy(mode = mode))
        _uiState.value = _uiState.value.copy(mode = mode)
        serviceController.restartWhenReady(getActiveSubscriptionId())
    }

    fun switchTunStack(stack: String) {
        if (_uiState.value.tunMode == TunMode.RootTproxy) return
        val current = overrideStore.load()
        val nextTun = (current.tun ?: TunOverride()).copy(stack = stack)
        overrideStore.save(current.copy(tun = nextTun))
        _uiState.value = _uiState.value.copy(tunStack = stack)
        serviceController.restart(getActiveSubscriptionId())
    }

    fun reloadConfig() {
        serviceController.restart(getActiveSubscriptionId())
    }

    // 延迟 = 经内核 mixed port 真实访问站点的耗时，只反映「现在走的这个出口」。
    // 自动触发仅两处：内核连接建立（启动/重启）与节点切换（nodeSwitches 信号），
    // 其余一律等用户手动点刷新；force 用来打断进行中的一轮测试立即重测。
    fun testLatency(force: Boolean = false) {
        if (latencyJob?.isActive == true) {
            if (!force) return
            latencyJob?.cancel()
        }
        if (repository == null) return
        // 完成回调按代次对账：force 打断旧测试时，旧任务的 finally 不得清掉新一轮的测试态。
        val generation = ++latencyGeneration
        _uiState.value = _uiState.value.copy(isTestingLatency = true)

        latencyJob = viewModelScope.launch {
            try {
                // 内核刚起来时端口解析可能还没就绪，短暂等待补一次，避免启动首测整轮落空。
                var endpoint = resolveProxyEndpoint()
                if (endpoint == null) {
                    delay(ENDPOINT_RETRY_DELAY)
                    endpoint = resolveProxyEndpoint()
                }
                if (endpoint == null) {
                    _uiState.value = _uiState.value.copy(latencyViaRules = false)
                    return@launch
                }
                val exitJob = async {
                    // 出口信息与延迟无关，单独抓一次 trace：ip= 是出口 IP，loc= 是国家/地区码。
                    var trace = latencyTester.probe(EXIT_PROBE_URL, endpoint, LATENCY_TIMEOUT_MILLIS)
                    if (trace == null) {
                        delay(PROBE_RETRY_DELAY)
                        trace = latencyTester.probe(EXIT_PROBE_URL, endpoint, LATENCY_TIMEOUT_MILLIS)
                    }
                    if (trace == null) clearExitInfo() else applyExitInfo(trace.body)
                }
                val viaRules = LatencyProbe.entries.map { probe ->
                    async {
                        var result = latencyTester.probe(probe.url, endpoint, LATENCY_TIMEOUT_MILLIS)
                        if (result == null) {
                            // 首个请求可能赶在隧道完全就绪前发出，稍候重试一次再定论。
                            delay(PROBE_RETRY_DELAY)
                            result = latencyTester.probe(probe.url, endpoint, LATENCY_TIMEOUT_MILLIS)
                        }
                        if (result == null) {
                            applyLatency(probe, -1)
                            false
                        } else {
                            applyLatency(probe, result.delay)
                            true
                        }
                    }
                }.awaitAll()
                exitJob.await()
                _uiState.value = _uiState.value.copy(latencyViaRules = viaRules.all { it })
            } finally {
                if (generation == latencyGeneration) {
                    _uiState.value = _uiState.value.copy(isTestingLatency = false)
                }
            }
        }
    }

    // 出口探测与延迟测试都走内核 mixed port：优先用运行时配置里已确认的端口，
    // 缺失时再回落到订阅代理解析器（override 声明或向内核 API 查询）。
    private suspend fun resolveProxyEndpoint(): String? {
        _uiState.value.config?.mixedPort?.takeIf { it > 0 }?.let { return "http://127.0.0.1:$it" }
        return latencyTester.resolveEndpoint()
    }

    // trace 的响应体自带出口信息：ip= 是代理出口 IP，loc= 是落地国家/地区码（界面转成国旗）。
    private fun applyExitInfo(body: String) {
        val ip = EXIT_IP_REGEX.find(body)?.groupValues?.get(1).orEmpty()
        val region = EXIT_REGION_REGEX.find(body)?.groupValues?.get(1).orEmpty()
        _uiState.update { it.copy(exitIp = ip, exitRegion = region) }
    }

    private fun clearExitInfo() {
        _uiState.update { it.copy(exitIp = "", exitRegion = "") }
    }

    private fun applyLatency(probe: LatencyProbe, delay: Int) {
        _uiState.update {
            when (probe) {
                LatencyProbe.Baidu -> it.copy(latencyBaidu = delay)
                LatencyProbe.Cloudflare -> it.copy(latencyCloudflare = delay)
                LatencyProbe.Google -> it.copy(latencyGoogle = delay)
            }
        }
    }

    private fun disconnectStreams() {
        initialLoadJob?.cancel()
        clearProviderTraffic()
        repositorySubscriptionId = null
        trafficJob?.cancel()
        memoryJob?.cancel()
        systemInfoJob?.cancel()
        runtimeConfigJob?.cancel()
        uptimeJob?.cancel()
        latencyJob?.cancel()
        _uiState.update { it.copy(isTestingLatency = false) }
        stopConnectionRateTracking()
        onLiveProviderInfo(null, null)
        mihomoPid = -1
    }

    companion object {
        const val TRAFFIC_HISTORY_CAPACITY = 60

        private const val LATENCY_TIMEOUT_MILLIS = 5000L

        private const val ENDPOINT_RETRY_DELAY = 1000L

        private const val PROBE_RETRY_DELAY = 1000L

        private val EXIT_IP_REGEX = Regex("^ip=(.+)$", RegexOption.MULTILINE)

        private val EXIT_REGION_REGEX = Regex("^loc=(.+)$", RegexOption.MULTILINE)

        private const val EXIT_PROBE_URL = "http://www.cloudflare.com/cdn-cgi/trace"

        private const val TOP_CONNECTION_COUNT = 5
    }
}
