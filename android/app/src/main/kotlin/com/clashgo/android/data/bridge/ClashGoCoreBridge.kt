package com.clashgo.android.data.bridge

import com.clashgo.android.domain.model.ChainProxyContext
import com.clashgo.android.domain.model.ProxyPreview
import com.clashgo.android.domain.model.RuleOverrideContext
import com.clashgo.android.service.ProfileFileOps
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class AgeKeyPair(val secretKey: String, val publicKey: String)

@Serializable
data class CoreFetchProgress(
    val action: String,
    val args: List<String> = emptyList(),
    val progress: Int = -1,
    val max: Int = -1,
)

@Serializable
data class CoreFetchResult(
    val upload: Long = 0,
    val download: Long = 0,
    val total: Long = 0,
    val expire: Long = 0,
    val fileName: String = "",
    val builtinChainProxyNames: List<String> = emptyList(),
)

@Serializable
private data class TransformCheck(val hasCycle: Boolean = false)

@Serializable
private data class ProxyNodeDelay(val delay: Int = 0)

class ClashGoCoreError(message: String) : RuntimeException(message)

object ClashGoCoreBridge {

    private val tokenSeq = AtomicInteger(1)
    private val json = Json { ignoreUnknownKeys = true }

    // 内核库很大（v1.19.32 起未压缩 70MB+），主线程 dlopen 会把冷启动的首屏帧堵死。
    // 因此加载与内核初始化放到后台完成，所有 native 入口先 awaitCoreReady() 等就绪，
    // 保证任何 native 调用都晚于 nativeCoreInit。Application 启动路径只负责触发。
    private val coreReady = CountDownLatch(1)

    // 加载失败时必须让等待方限时失败而不是无限阻塞（否则 9 个 native 入口全部永久挂起，主线程调用点直接 ANR）。
    @Volatile
    private var initFailure: Throwable? = null

    fun init(homeDir: String) {
        thread(name = "core-bridge-init") {
            try {
                // 加载顺序不能换：桥接库链接了 mihomo 库里的符号，必须先把 mihomo 库装进来。
                System.loadLibrary("mihomo")
                System.loadLibrary("stelliberty_jni")
                nativeCoreInit(homeDir)
            } catch (t: Throwable) {
                initFailure = t
            } finally {
                coreReady.countDown()
            }
        }
    }

    private fun awaitCoreReady() {
        initFailure?.let {
            throw ClashGoCoreError("core init failed: ${it.message}")
        }
        if (!coreReady.await(CORE_INIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw ClashGoCoreError("core init timed out after ${CORE_INIT_TIMEOUT_SECONDS}s")
        }
    }

    // 取消钩子必须在阻塞调用之前挂好：这个原生调用不响应协程取消，等它返回时下层已经清掉了取消登记表，
    // 那时再喊停就是空操作，锁会被一直占到超时。
    suspend fun fetchAndValid(
        workDir: String,
        url: String,
        force: Boolean,
        httpProxy: String?,
        userAgent: String,
        ageSecretKey: String,
        onProgress: suspend (CoreFetchProgress) -> Unit,
    ): CoreFetchResult = coroutineScope {
        awaitCoreReady()
        val token = tokenSeq.getAndIncrement()
        val nativeDone = AtomicBoolean(false)
        val canceller = launch {
            try {
                awaitCancellation()
            } finally {
                if (!nativeDone.get()) runCatching { nativeCancel(token) }
            }
        }
        val pollerJob = launchProgressPoller(this, token, onProgress)
        try {
            val raw = withContext(Dispatchers.IO) {
                ProfileFileOps.awaitGeodata()
                try {
                    nativeFetchAndValid(workDir, url, force, httpProxy, userAgent, ageSecretKey, token)
                } finally {
                    nativeDone.set(true)
                }
            }
            decodePayload(raw, CoreFetchResult.serializer())
        } finally {
            canceller.cancel()
            pollerJob.cancel()
        }
    }

    private fun launchProgressPoller(
        scope: CoroutineScope,
        token: Int,
        onProgress: suspend (CoreFetchProgress) -> Unit,
    ) = scope.launch(Dispatchers.IO) {
        var last: String? = null
        while (isActive) {
            val current = nativeQueryProgress(token)
            if (current != null && current != last) {
                last = current
                runCatching { json.decodeFromString(CoreFetchProgress.serializer(), current) }
                    .onSuccess { onProgress(it) }
            }
            delay(PROGRESS_POLL_INTERVAL_MS)
        }
    }

    @JvmStatic
    private external fun nativeCoreInit(homeDir: String)

    @JvmStatic
    private external fun nativeFetchAndValid(
        workDir: String,
        url: String,
        force: Boolean,
        httpProxy: String?,
        userAgent: String,
        ageSecretKey: String,
        token: Int,
    ): String?

    @JvmStatic
    private external fun nativeCancel(token: Int)

    @JvmStatic
    private external fun nativeQueryProgress(token: Int): String?

    fun generateAgeKeyPair(hybrid: Boolean = false): AgeKeyPair? {
        awaitCoreReady()
        val raw = (if (hybrid) nativeGenAgeHybridKeyPair() else nativeGenAgeKeyPair()) ?: return null
        if (raw.startsWith("error:")) return null
        val lines = raw.split("\n")
        if (lines.size < 2) return null
        val secret = lines[0].trim()
        val public = lines[1].trim()
        if (secret.isEmpty() || public.isEmpty()) return null
        return AgeKeyPair(secretKey = secret, publicKey = public)
    }

    // 把 age 加密的配置解到 target，未加密的原样复制；失败抛 ClashGoCoreError。
    fun decryptFile(source: File, target: File, secretKey: String) {
        awaitCoreReady()
        val raw = nativeDecryptFile(source.path, target.path, secretKey)
        if (raw != null && raw.startsWith("error:")) {
            throw ClashGoCoreError(raw.removePrefix("error:").trim())
        }
    }

    // 从 APK 里解出 xz 压缩的 asset 写到 target，失败抛 ClashGoCoreError。
    fun extractXzAsset(apkPath: String, entry: String, target: File) {
        awaitCoreReady()
        val raw = nativeExtractXzAsset(apkPath, entry, target.path)
        if (raw != null && raw.startsWith("error:")) {
            throw ClashGoCoreError(raw.removePrefix("error:").trim())
        }
    }

    @JvmStatic
    private external fun nativeExtractXzAsset(apkPath: String, entry: String, target: String): String?

    @JvmStatic
    private external fun nativeDecryptFile(source: String, target: String, secretKey: String): String?

    // 与运行时同一套变换与解析流程，失败抛 ClashGoCoreError；返回变换后的配置是否含链式代理循环引用。
    fun validateTransform(workDir: File, transform: File, ageSecretKey: String): Boolean {
        awaitCoreReady()
        ProfileFileOps.awaitGeodata()
        val raw = nativeValidateTransform(workDir.path, transform.path, ageSecretKey)
        return decodePayload(raw, TransformCheck.serializer()).hasCycle
    }

    // transform 为 null 时直接取订阅原文。
    fun chainProxyContext(workDir: File, transform: File?, ageSecretKey: String): ChainProxyContext {
        awaitCoreReady()
        return decodePayload(
            nativeChainProxyContext(workDir.path, transform?.path.orEmpty(), ageSecretKey),
            ChainProxyContext.serializer(),
        )
    }

    // transform 只带覆写与链式代理，得到的是规则覆写之前的基线。
    fun ruleContext(workDir: File, transform: File?, ageSecretKey: String): RuleOverrideContext {
        awaitCoreReady()
        return decodePayload(
            nativeRuleContext(workDir.path, transform?.path.orEmpty(), ageSecretKey),
            RuleOverrideContext.serializer(),
        )
    }

    // 内核未运行时代理页的静态预览，与 ruleContext 同一份配置口径，provider 成员从缓存文件补齐。
    fun proxyPreview(workDir: File, transform: File?, ageSecretKey: String): ProxyPreview {
        awaitCoreReady()
        return decodePayload(
            nativeProxyPreview(workDir.path, transform?.path.orEmpty(), ageSecretKey),
            ProxyPreview.serializer(),
        )
    }

    // 内核未运行时的节点测速：应用进程内组装真实出口跑内核 URLTest，与运行时 /proxies/{name}/delay 同口径。
    // 阻塞式 JNI 调用（不响应协程取消），调用方须留在 Dispatchers.IO 并自行限并发。
    fun proxyNodeDelay(
        workDir: File,
        transform: File?,
        ageSecretKey: String,
        nodeName: String,
        testUrl: String,
        timeoutMs: Int,
    ): Int {
        awaitCoreReady()
        return decodePayload(
            nativeProxyNodeDelay(
                workDir.path,
                transform?.path.orEmpty(),
                ageSecretKey,
                nodeName,
                testUrl,
                timeoutMs,
            ),
            ProxyNodeDelay.serializer(),
        ).delay
    }

    private fun <T> decodePayload(raw: String?, serializer: KSerializer<T>): T {
        if (raw.isNullOrEmpty()) throw ClashGoCoreError("native returned empty result")
        if (raw.startsWith("error:")) throw ClashGoCoreError(raw.removePrefix("error:").trim())
        return runCatching { json.decodeFromString(serializer, raw) }
            .getOrElse { throw ClashGoCoreError("invalid native payload: $raw") }
    }

    @JvmStatic
    private external fun nativeValidateTransform(workDir: String, transform: String, secretKey: String): String?

    @JvmStatic
    private external fun nativeChainProxyContext(workDir: String, transform: String, secretKey: String): String?

    @JvmStatic
    private external fun nativeRuleContext(workDir: String, transform: String, secretKey: String): String?

    @JvmStatic
    private external fun nativeProxyPreview(workDir: String, transform: String, secretKey: String): String?

    @JvmStatic
    private external fun nativeProxyNodeDelay(
        workDir: String,
        transform: String,
        secretKey: String,
        nodeName: String,
        testUrl: String,
        timeoutMs: Int,
    ): String?

    @JvmStatic
    private external fun nativeGenAgeKeyPair(): String?

    @JvmStatic
    private external fun nativeGenAgeHybridKeyPair(): String?

    private const val PROGRESS_POLL_INTERVAL_MS = 150L

    // 正常加载在数百毫秒内完成；给低端机留足余量，失败也要有限时间把错误抛给调用方。
    private const val CORE_INIT_TIMEOUT_SECONDS = 10L
}
