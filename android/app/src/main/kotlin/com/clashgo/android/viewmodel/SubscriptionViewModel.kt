package com.clashgo.android.viewmodel

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clashgo.android.R
import com.clashgo.android.data.repository.ConfigValidationException
import com.clashgo.android.data.repository.ImportError
import com.clashgo.android.data.repository.ImportProgress
import com.clashgo.android.data.repository.ProfileProcessor
import com.clashgo.android.domain.model.Subscription
import com.clashgo.android.domain.model.SubscriptionAutoUpdateMode
import com.clashgo.android.domain.model.SubscriptionUpdateProxyMode
import com.clashgo.android.domain.repository.SubscriptionRepository
import com.clashgo.android.platform.ProfileFileManager
import com.clashgo.android.platform.ProxyServiceController
import com.clashgo.android.platform.showToast
import com.clashgo.android.util.AppLogger
import com.clashgo.android.util.describe
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ProfileOperation { Import, Update, Edit }

@Immutable
data class SubscriptionUiState(
    val subscriptions: ImmutableList<Subscription> = persistentListOf(),
    val currentId: String? = null,
    val isLoading: Boolean = false,
    val error: String = "",
    val showAddDialog: Boolean = false,
    val importProgress: ImportProgress? = null,
    val operation: ProfileOperation? = null,
    // 正在更新的订阅 id：驱动对应卡片的刷新按钮旋转，其余卡片保持不动。
    val updatingIds: ImmutableSet<String> = persistentSetOf(),
)

class SubscriptionViewModel(
    private val repository: SubscriptionRepository,
    val fileManager: ProfileFileManager,
    private val processor: ProfileProcessor,
    private val serviceController: ProxyServiceController,
    private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SubscriptionUiState())
    val uiState: StateFlow<SubscriptionUiState> = _uiState.asStateFlow()

    private var currentJob: Job? = null

    private var startupUpdatesStarted = false

    init {
        viewModelScope.launch {
            combine(repository.subscriptions, repository.currentSubscriptionId, ::Pair).collect { (subs, id) ->
                _uiState.value = _uiState.value.copy(subscriptions = subs, currentId = id)
            }
        }
    }

    fun showAddDialog() {
        _uiState.value = _uiState.value.copy(showAddDialog = true)
    }

    fun hideAddDialog() {
        _uiState.value = _uiState.value.copy(showAddDialog = false)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = "")
    }

    // 必须同步清掉进度状态让对话框立刻消失：协程取消是异步的，如果正卡在不可取消的提交阶段
    // 或者文件读写上，等收尾时再清会让用户觉得点了取消没反应。
    fun cancelCurrentUpdate() {
        clearProgress()
        currentJob?.cancel()
    }

    fun addSubscription(
        name: String,
        url: String,
        autoUpdateMode: SubscriptionAutoUpdateMode = SubscriptionAutoUpdateMode.Disabled,
        intervalMinutes: Int = 0,
        userAgent: String = "",
        ageSecretKey: String = "",
        updateProxyMode: SubscriptionUpdateProxyMode = SubscriptionUpdateProxyMode.Core,
        autoTestDelayMinutes: Int = 0,
        onComplete: () -> Unit = {},
    ) {
        hideAddDialog()
        runPipeline(ProfileOperation.Import, errorKey = R.string.error_import_failed) {
            val sub = repository.create(
                name = name,
                sourceLocation = url,
                isLocalFile = false,
                autoUpdateMode = autoUpdateMode,
                autoUpdateIntervalMinutes = intervalMinutes,
                userAgent = userAgent,
                ageSecretKey = ageSecretKey,
                updateProxyMode = updateProxyMode,
                autoTestDelayIntervalMinutes = autoTestDelayMinutes,
            )
            pendingOnFailure(sub.id) {
                processor.apply(sub.id, ::reportProgress)
            }
            onComplete()
        }
    }

    fun addFromFile(fileName: String, content: String, onComplete: () -> Unit = {}) {
        runPipeline(ProfileOperation.Import, errorKey = R.string.error_import_failed) {
            val name = fileName.removeSuffix(".yaml").removeSuffix(".yml")
            val sub = repository.create(name = name, sourceLocation = "", isLocalFile = true)
            pendingOnFailure(sub.id) {
                fileManager.savePendingConfig(sub.id, content)
                processor.apply(sub.id, ::reportProgress)
            }
            onComplete()
        }
    }

    fun fetchSubscription(id: String) {
        val sub = _uiState.value.subscriptions.find { it.id == id } ?: return
        if (sub.isLocalFile) {
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(error = context.getString(R.string.subscription_file_only_no_update))
            }
            return
        }
        runPipeline(
            ProfileOperation.Update,
            errorKey = R.string.error_update_failed,
            successMessage = context.getString(R.string.subscription_update_success),
        ) {
            setUpdating(id, true)
            try {
                processor.update(id, ::reportProgress)
                serviceController.restartAfterProfileUpdate(id)
            } finally {
                setUpdating(id, false)
            }
        }
    }

    // 卡片旋转标记：add/remove 都走整值替换，ImmutableSet 才能触发 Compose 重组。
    private fun setUpdating(id: String, updating: Boolean) {
        _uiState.value = _uiState.value.copy(
            updatingIds = if (updating) {
                (_uiState.value.updatingIds + id).toPersistentSet()
            } else {
                (_uiState.value.updatingIds - id).toPersistentSet()
            },
        )
    }

    fun removeSubscription(id: String, onActiveChanged: () -> Unit = {}) {
        viewModelScope.launch {
            val wasActive = repository.currentSubscriptionId.value == id
            repository.delete(id)
            fileManager.deleteDirs(id)
            if (wasActive) onActiveChanged()
        }
    }

    fun setActive(id: String) {
        repository.setActive(id)
    }

    fun updateAllSubscriptions() {
        updateSubscriptions(_uiState.value.subscriptions.filterNot { it.isLocalFile })
    }

    // 「启动时更新」按打开应用计：ViewModel 是进程级单例，每个进程只跑一次。
    fun runStartupUpdates() {
        if (startupUpdatesStarted) return
        startupUpdatesStarted = true
        updateSubscriptions(
            repository.subscriptions.value.filter {
                !it.isLocalFile && it.autoUpdateMode == SubscriptionAutoUpdateMode.Startup
            }
        )
    }

    // 全部目标并发更新：每个订阅各占一条协程（处理目录已按 uuid 隔离），卡片各自的刷新按钮旋转。
    // 让配置生效的重启仍排在整个批次结束之后：中途重启会断网，还没下载完的订阅会跟着失败。
    private fun updateSubscriptions(targets: List<Subscription>) {
        if (targets.isEmpty()) return
        if (isBusy()) return

        _uiState.value = _uiState.value.copy(operation = ProfileOperation.Update, error = "")
        currentJob = viewModelScope.launch {
            val failures = mutableListOf<String>()
            val updated = mutableListOf<String>()
            try {
                coroutineScope {
                    targets.forEach { sub ->
                        launch {
                            setUpdating(sub.id, true)
                            try {
                                processor.update(sub.id)
                                updated += sub.id
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                AppLogger.warn(TAG, "Batch update failed for ${sub.id}", e)
                                val label = if (e is ConfigValidationException) {
                                    context.getString(R.string.error_validation_failed, e.describe())
                                } else {
                                    context.getString(R.string.error_update_failed, localizedMessage(e))
                                }
                                // failures/updated 只会在主线程协程里追加（viewModelScope 固定 Main 调度），无需同步。
                                failures += "${sub.name}: $label"
                            } finally {
                                setUpdating(sub.id, false)
                            }
                        }
                    }
                }
                _uiState.value = _uiState.value.copy(
                    operation = null,
                    error = if (failures.isEmpty()) "" else failures.joinToString("\n"),
                )
                // 批量更新（手动「全部更新」与启动时自动更新）走 Toast：页面外也能收到结果。
                if (failures.isEmpty()) {
                    showToast(context.getString(R.string.subscription_update_success))
                } else {
                    showToast(failures.joinToString("\n"), long = true)
                }
                updated.forEach { serviceController.restartAfterProfileUpdate(it) }
            } catch (e: CancellationException) {
                AppLogger.info(TAG, "Batch profile update cancelled")
                clearProgress()
                throw e
            } finally {
                currentJob = null
            }
        }
    }

    fun editSubscription(
        uuid: String,
        name: String,
        source: String,
        autoUpdateMode: SubscriptionAutoUpdateMode,
        intervalMinutes: Int,
        userAgent: String,
        ageSecretKey: String,
        updateProxyMode: SubscriptionUpdateProxyMode,
        autoTestDelayMinutes: Int,
        onComplete: () -> Unit = {},
    ) {
        runPipeline(ProfileOperation.Edit, errorKey = R.string.error_save_failed) {
            repository.patch(
                uuid, name, source, autoUpdateMode, intervalMinutes, userAgent, ageSecretKey, updateProxyMode,
                autoTestDelayMinutes,
            )
            if (repository.validatePendingForCommit(uuid)) {
                processor.apply(uuid, ::reportProgress)
            } else {
                repository.commitPendingProfile(uuid)
            }
            onComplete()
        }
    }

    private fun runPipeline(
        op: ProfileOperation,
        @StringRes errorKey: Int,
        successMessage: String? = null,
        block: suspend () -> Unit,
    ) {
        if (isBusy()) return
        _uiState.value = _uiState.value.copy(
            isLoading = true,
            error = "",
            importProgress = null,
            operation = op,
        )
        currentJob = viewModelScope.launch {
            AppLogger.info(TAG, "Profile operation $op started")
            try {
                block()
                AppLogger.info(TAG, "Profile operation $op completed")
                successMessage?.let { showToast(it) }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    importProgress = null,
                    operation = null,
                )
            } catch (e: CancellationException) {
                AppLogger.info(TAG, "Profile operation $op cancelled")
                clearProgress()
                throw e
            } catch (e: Throwable) {
                AppLogger.error(TAG, "Profile operation $op failed", e)
                val message = if (e is ConfigValidationException) {
                    context.getString(R.string.error_validation_failed, e.describe())
                } else {
                    context.getString(errorKey, localizedMessage(e))
                }
                // 失败同步 Toast：订阅页顶部错误横幅已移除，导入/更新失败只靠这里反馈。
                // Edit 操作的失败仍由编辑页自己的横幅展示，不重复弹。
                if (op != ProfileOperation.Edit) showToast(message, long = true)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    importProgress = null,
                    operation = null,
                    error = message,
                )
            } finally {
                currentJob = null
            }
        }
    }

    private fun localizedMessage(e: Throwable): String = when (e) {
        is ImportError.HttpStatus -> context.getString(R.string.error_import_http_status, e.code)
        is ImportError.EmptyBody -> context.getString(R.string.error_import_empty_body)
        is ImportError.InvalidScheme -> context.getString(R.string.error_import_invalid_scheme, e.source)
        is ImportError.InvalidName -> context.getString(R.string.error_import_invalid_name)
        is ImportError.IntervalTooSmall -> context.getString(R.string.error_import_interval_too_small)
        else -> e.describe()
    }

    private suspend fun pendingOnFailure(uuid: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            withContext(NonCancellable) {
                runCatching {
                    repository.release(uuid)
                    fileManager.releasePending(uuid)
                }
            }
            throw e
        }
    }

    private fun isBusy(): Boolean = currentJob?.isActive == true

    private fun clearProgress() {
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            importProgress = null,
            operation = null,
            updatingIds = persistentSetOf(),
        )
    }

    private fun reportProgress(p: ImportProgress) {
        _uiState.value = _uiState.value.copy(importProgress = p)
    }

    private companion object {
        const val TAG = "SubscriptionViewModel"
    }
}
