package com.clashgo.android.data.repository

import com.clashgo.android.data.bridge.CoreFetchProgress
import com.clashgo.android.data.bridge.ClashGoCoreBridge
import com.clashgo.android.data.bridge.ClashGoCoreError
import com.clashgo.android.domain.model.Subscription
import com.clashgo.android.domain.model.SubscriptionTrafficInfo
import com.clashgo.android.domain.model.SubscriptionUpdateProxyMode
import com.clashgo.android.platform.ProfileFileManager
import com.clashgo.android.util.AppLogger
import com.clashgo.android.util.describe
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class ImportStep { Downloading, Prefetching, Validating, Other }

data class ImportProgress(
    val step: ImportStep,
    val providerName: String = "",
    val rawLabel: String = "",
    val current: Int = 0,
    val total: Int = 0,
)

class ConfigValidationException(message: String) : Exception(message)

class ProfileProcessor(
    private val repo: SubscriptionRepositoryImpl,
    private val fileManager: ProfileFileManager,
    private val defaultProfileName: String,
    private val proxyResolver: SubscriptionProxyResolver,
) {

    // 启动时清理：各订阅的处理残留，以及没人认领的订阅目录（删订阅是先删列表条目再删目录两步，
    // 中间进程挂了就会留下孤儿）。处理目录已按 uuid 隔离，按 uuid 加锁清理即可与并发更新互斥。
    suspend fun cleanupResidual() = withContext(Dispatchers.IO) {
        val known = repo.knownUuidsOrNull()
        if (known == null) {
            AppLogger.warn(TAG, "Subscription list incomplete, skipping residual cleanup")
            return@withContext
        }
        known.forEach { uuid ->
            processLockOf(uuid).withLock { fileManager.cleanupProcessing(uuid) }
        }
        val orphans = fileManager.deleteOrphanDirs(known)
        if (orphans.isNotEmpty()) AppLogger.info(TAG, "Removed orphan profile dirs: $orphans")
    }

    suspend fun apply(uuid: String, onProgress: (ImportProgress) -> Unit = {}) {
        runProcess(uuid, isUpdate = false, onProgress)
    }

    suspend fun update(uuid: String, onProgress: (ImportProgress) -> Unit = {}) {
        runProcess(uuid, isUpdate = true, onProgress)
    }

    private suspend fun runProcess(
        uuid: String,
        isUpdate: Boolean,
        onProgress: (ImportProgress) -> Unit,
    ) = withContext(Dispatchers.IO) {
        // 处理目录与提交暂存都按 uuid 隔离，这里只挡同一订阅的重入；不同订阅可以真正并发。
        processLockOf(uuid).withLock {
            val (snapshot, workDir) = repo.withProfileLock {
                if (isUpdate) {
                    val imported = repo.queryImported(uuid)
                        ?: throw IllegalArgumentException("Profile $uuid not found")
                    val snap = PendingSnapshot.of(imported)
                    val dir = fileManager.prepareProcessing(uuid)
                    fileManager.readImportedFile(uuid, "config.yaml")?.let {
                        fileManager.writeProcessingConfig(dir, it)
                    }
                    snap to dir
                } else {
                    val pending = repo.queryPending(uuid)
                        ?: throw IllegalArgumentException("No pending profile for $uuid")
                    pending.enforceFieldValid()
                    val dir = fileManager.prepareProcessing(uuid)
                    PendingSnapshot.of(pending) to dir
                }
            }

            try {
                val started = TimeSource.Monotonic.markNow()
                val proxyUrl = if (snapshot.isLocalFile) null
                else proxyResolver.resolveForSubscription(snapshot.updateProxyMode, snapshot.sourceLocation)
                AppLogger.info(
                    TAG,
                    "Fetching profile $uuid: update=$isUpdate, source=${if (snapshot.isLocalFile) "file" else "url"}, " +
                        "updateProxyMode=${snapshot.updateProxyMode}, proxy=${proxyUrl ?: "direct"}, " +
                        "customUserAgent=${snapshot.userAgent.isNotBlank()}, encrypted=${snapshot.ageSecretKey.isNotBlank()}",
                )

                val result = try {
                    ClashGoCoreBridge.fetchAndValid(
                        workDir = workDir,
                        url = if (snapshot.isLocalFile) "" else snapshot.sourceLocation,
                        force = !snapshot.isLocalFile,
                        httpProxy = proxyUrl,
                        userAgent = snapshot.userAgent,
                        ageSecretKey = snapshot.ageSecretKey,
                        onProgress = { p -> onProgress(mapProgress(p)) },
                    )
                } catch (e: ClashGoCoreError) {
                    throw translateCoreError(e)
                }

                val trafficInfo = SubscriptionTrafficInfo.ofOrNull(
                    result.upload, result.download, result.total, result.expire,
                )
                withContext(NonCancellable) {
                    repo.withProfileLock {
                        if (isUpdate) {
                            val current = repo.queryImported(uuid)
                                ?: throw IllegalArgumentException("Imported profile $uuid disappeared during update")
                            check(current.id == snapshot.uuid)
                            fileManager.commitProcessingToImported(uuid)
                            repo.markUpdated(uuid, trafficInfo, result.builtinChainProxyNames)
                        } else {
                            val currentPending = repo.queryPending(uuid)
                                ?: throw IllegalArgumentException("Pending profile $uuid disappeared during commit")
                            check(currentPending.id == snapshot.uuid)
                            fileManager.commitProcessingToImported(uuid)
                            repo.commitPending(
                                uuid = uuid,
                                fetched = true,
                                trafficInfo = trafficInfo,
                                builtinChainProxyNames = result.builtinChainProxyNames,
                                fallbackName = autoProfileName(snapshot, result.fileName),
                            )
                        }
                    }
                }
                AppLogger.info(
                    TAG,
                    "Profile $uuid committed in ${started.elapsedNow().inWholeMilliseconds}ms: " +
                        "traffic=${trafficInfo != null}, builtinChains=${result.builtinChainProxyNames.size}",
                )
            } catch (t: Throwable) {
                if (t !is CancellationException) {
                    AppLogger.error(TAG, "Profile pipeline failed for $uuid (update=$isUpdate)", t)
                }
                withContext(NonCancellable) {
                    fileManager.cleanupProcessing(uuid)
                    if (isUpdate && t !is CancellationException) {
                        repo.withProfileLock { repo.markFailed(uuid, t.describe()) }
                    }
                }
                throw t
            }
        }
    }

    private fun autoProfileName(snapshot: PendingSnapshot, dispositionName: String): String {
        if (snapshot.isLocalFile) return ""
        return dispositionName
            .ifBlank { runCatching { URI(snapshot.sourceLocation).host.orEmpty() }.getOrDefault("") }
            .ifBlank { defaultProfileName }
    }

    // Go 侧只能返回英文字符串，界面要按语言显示就得在这里认出来换成带类型的异常。
    // 这几条文案由 native/stelliberty_core/fetch.go 产生，改那边必须同步改这里。
    private fun translateCoreError(e: ClashGoCoreError): Throwable {
        val msg = e.message ?: return e
        return when {
            msg.startsWith("validate config:") ->
                ConfigValidationException(msg.removePrefix("validate config:").trim())

            msg == "empty response body" -> ImportError.EmptyBody()

            msg.startsWith("http status ") ->
                msg.removePrefix("http status ").trim().toIntOrNull()
                    ?.let { ImportError.HttpStatus(it) } ?: e

            else -> e
        }
    }

    private fun mapProgress(p: CoreFetchProgress): ImportProgress = when (p.action) {
        "FetchConfiguration" -> ImportProgress(ImportStep.Downloading)
        "FetchProviders" -> ImportProgress(
            step = ImportStep.Prefetching,
            providerName = p.args.firstOrNull().orEmpty(),
            current = p.progress,
            total = p.max,
        )

        "Verifying" -> ImportProgress(ImportStep.Validating)
        else -> ImportProgress(ImportStep.Other, rawLabel = p.action)
    }

    companion object {
        // 全进程锁：备份/恢复、覆写与规则保存用。注意订阅更新（runProcess）已改为按 uuid 加锁，
        // 不再持有这把锁——恢复备份若与某个更新恰好并发，该更新会因目录被替换而失败报错，不会损坏数据。
        private val processLock = Mutex()

        // 每个 uuid 一把锁：同一订阅的更新互斥，不同订阅的更新可以真正并发。
        private val processLocks = ConcurrentHashMap<String, Mutex>()

        private fun processLockOf(uuid: String): Mutex = processLocks.computeIfAbsent(uuid) { Mutex() }

        private const val TAG = "ProfileProcessor"

        suspend fun <T> withProcessLock(block: suspend () -> T): T = processLock.withLock { block() }
    }
}

internal data class PendingSnapshot(
    val uuid: String,
    val isLocalFile: Boolean,
    val sourceLocation: String,
    val userAgent: String,
    val ageSecretKey: String,
    val updateProxyMode: SubscriptionUpdateProxyMode,
) {
    companion object {
        fun of(subscription: Subscription) = PendingSnapshot(
            uuid = subscription.id,
            isLocalFile = subscription.isLocalFile,
            sourceLocation = subscription.sourceLocation,
            userAgent = subscription.userAgent,
            ageSecretKey = subscription.ageSecretKey,
            updateProxyMode = subscription.updateProxyMode,
        )
    }
}
