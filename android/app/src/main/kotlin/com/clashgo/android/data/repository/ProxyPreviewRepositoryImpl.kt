package com.clashgo.android.data.repository

import com.clashgo.android.data.bridge.ClashGoCoreBridge
import com.clashgo.android.data.store.ProfileTransformWriter
import com.clashgo.android.data.store.SubscriptionStore
import com.clashgo.android.domain.model.ProxyPreview
import com.clashgo.android.domain.model.resolveDelayTestUrl
import com.clashgo.android.domain.repository.ProxyPreviewRepository
import com.clashgo.android.platform.ProfileFileManager
import com.clashgo.android.util.AppLogger
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProxyPreviewRepositoryImpl(
    private val subscriptionStore: SubscriptionStore,
    private val transformWriter: ProfileTransformWriter,
    private val fileManager: ProfileFileManager,
    private val overrideStore: OverrideJsonStore,
) : ProxyPreviewRepository {

    // 与运行时同一套变换（覆写 + 链式代理）；规则覆写只动 rules 段，预览不需要套。
    override suspend fun load(subscriptionId: String): ProxyPreview? = withContext(Dispatchers.IO) {
        val subscription = subscriptionStore.findImported(subscriptionId) ?: return@withContext null
        val transform = transformWriter.write(subscription, uniqueTransformName(), rules = null)
        try {
            ClashGoCoreBridge.proxyPreview(
                workDir = File(fileManager.getImportedDir(subscription.id)),
                transform = transform?.let(::File),
                ageSecretKey = subscription.ageSecretKey,
            )
        } catch (e: Exception) {
            AppLogger.warn(TAG, "load proxy preview failed", e)
            null
        } finally {
            transform?.let { File(it).delete() }
        }
    }

    // 每次调用现场重取变换文件：JNI 侧按 workDir + transform 读配置，测速本身远比这一读一写贵。
    // 文件名必须每次唯一：组测速并发跑多份"写→读→删"，固定名会让任一协程删掉别人还没读完的文件，
    // 或让读方拿到半截 YAML，表现为大批节点随机假超时。
    override suspend fun testNodeDelay(
        subscriptionId: String,
        nodeName: String,
        timeoutMs: Int,
    ): Int? = withContext(Dispatchers.IO) {
        val subscription = subscriptionStore.findImported(subscriptionId) ?: return@withContext null
        val transform = transformWriter.write(subscription, uniqueTransformName(), rules = null)
        try {
            ClashGoCoreBridge.proxyNodeDelay(
                workDir = File(fileManager.getImportedDir(subscription.id)),
                transform = transform?.let(::File),
                ageSecretKey = subscription.ageSecretKey,
                nodeName = nodeName,
                testUrl = overrideStore.load().resolveDelayTestUrl(),
                timeoutMs = timeoutMs,
            )
        } catch (e: Exception) {
            AppLogger.warn(TAG, "preview node delay test failed", e)
            null
        } finally {
            transform?.let { File(it).delete() }
        }
    }

    private companion object {
        const val TAG = "ProxyPreviewRepository"

        // 并发场景下每次调用生成唯一文件名，杜绝"写→读→删"对同一固定路径的交叉竞争。
        fun uniqueTransformName(): String = "proxy.preview.${java.util.UUID.randomUUID()}.json"
    }
}
