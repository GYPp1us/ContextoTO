package site.arcol.contextoto

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class RetainedQueriesTest {
    @Test fun leavingPopupDoesNotCancelWorkerAndReopeningReusesIt() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val queries = RetainedQueries(scope)
            val began = CompletableDeferred<Unit>()
            val reply = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val firstPopup = launch {
                queries.await("same", "n01", "bank", true, {}) { report ->
                    calls.incrementAndGet()
                    report(QueryProgress(QueryPhase.FIRST, 88))
                    began.complete(Unit)
                    reply.await()
                    JSONObject().put("ok", true)
                }
            }
            began.await()
            firstPopup.cancelAndJoin()
            assertEquals(QueryStatus.RUNNING, queries.tasks.value.single().status)
            assertTrue(queries.tasks.value.single().silent)
            val secondPopup = launch {
                val result = queries.await("same", "n01", "bank", false, {}) {
                    fail("A duplicate request was started")
                    JSONObject()
                }
                assertTrue(result.getBoolean("ok"))
            }
            // Ensure the second observer has attached before releasing the worker.
            kotlinx.coroutines.yield()
            assertFalse(queries.tasks.value.single().silent)
            reply.complete(Unit)
            secondPopup.join()
            assertEquals(1, calls.get())
            assertEquals(QueryStatus.COMPLETE, queries.tasks.value.single().status)
        } finally { scope.cancel() }
    }
}
