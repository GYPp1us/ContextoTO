package site.arcol.contextoto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

enum class QueryStatus { RUNNING, COMPLETE, FAILED }
data class QueryTask(val id: String, val articleId: String, val label: String, val silent: Boolean,
                     val progress: QueryProgress, val status: QueryStatus = QueryStatus.RUNNING,
                     val error: String? = null, val startedAt: Long = System.currentTimeMillis(),
                     val requestedAt: Long = startedAt) {
    val percent: Int get() = if (status == QueryStatus.COMPLETE) 100 else queryPercent(progress).coerceAtMost(97)
}

/** The worker's scope is independent of any popup/observer, so leaving a popup only detaches it. */
class RetainedQueries(private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
    private class Work(val progress: MutableStateFlow<QueryProgress>, val result: Deferred<JSONObject>, var task: QueryTask)
    private val work = LinkedHashMap<String, Work>()
    private val snapshots = MutableStateFlow<List<QueryTask>>(emptyList())
    val tasks: StateFlow<List<QueryTask>> = snapshots.asStateFlow()

    private fun publish() {
        snapshots.value = work.values.map { it.task }.sortedByDescending { it.requestedAt }.take(16)
    }

    suspend fun await(id: String, articleId: String, label: String, silent: Boolean,
                      onProgress: (QueryProgress) -> Unit,
                      operation: suspend ((QueryProgress) -> Unit) -> JSONObject): JSONObject {
        val retained = synchronized(work) {
            work[id]?.takeIf { it.result.isActive }?.also { existing ->
                if (!silent) {
                    existing.task = existing.task.copy(silent = false, requestedAt = System.currentTimeMillis())
                    publish()
                }
            } ?: run {
                val initial = QueryProgress(QueryPhase.CONTEXT)
                val progress = MutableStateFlow(initial)
                lateinit var created: Work
                val result = scope.async(start = CoroutineStart.LAZY) {
                    try {
                        val value = operation { update ->
                            progress.value = update
                            synchronized(work) { created.task = created.task.copy(progress = update); publish() }
                        }
                        synchronized(work) {
                            created.task = created.task.copy(status = QueryStatus.COMPLETE,
                                progress = created.task.progress.copy(phase = QueryPhase.COMPLETE)); publish()
                        }
                        value
                    } catch (error: Exception) {
                        synchronized(work) {
                            created.task = created.task.copy(status = QueryStatus.FAILED,
                                error = error.message ?: "查询失败"); publish()
                        }
                        throw error
                    }
                }
                created = Work(progress, result, QueryTask(id, articleId, label, silent, initial))
                work[id] = created
                // Retain all live work, but bound completed-task history.
                work.entries.filter { !it.value.result.isActive && it.key != id }.dropLast(15)
                    .map { it.key }.forEach(work::remove)
                publish()
                result.start()
                created
            }
        }
        return coroutineScope {
            val observer = launch { retained.progress.collect(onProgress) }
            try { retained.result.await() } finally { observer.cancel() }
        }
    }
}
