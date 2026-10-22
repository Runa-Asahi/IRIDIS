package com.iris.localai.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 2.1.8: the one-token warm-up at the end of a model load, bounded. Before, it ran unbounded inside
 * the load (`runCatching { warmUp(e) }`): an engine whose first generation never answered (NPU /
 * GPU runtime stuck before its first token) kept the load "in flight" forever, so a message sent
 * meanwhile — which waits for that load (2.1.7 InFlightLoad) — hung with it. The runCatching also
 * swallowed the load's own cancellation. Now: at most [TIMEOUT_MS]; on timeout the engine is asked
 * to stop ([onTimeout]) and the load goes on without warm-up; cancellation propagates. Pure.
 */
object WarmUpGuard {
    enum class Outcome { DONE, FAILED, TIMED_OUT }

    const val TIMEOUT_MS = 60_000L

    suspend fun run(timeoutMs: Long = TIMEOUT_MS, onTimeout: () -> Unit, block: suspend () -> Unit): Outcome {
        var failed = false
        val finished = withTimeoutOrNull(timeoutMs) {
            try {
                block()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                failed = true
            }
            true
        }
        if (finished == null) {
            runCatching(onTimeout)
            return Outcome.TIMED_OUT
        }
        return if (failed) Outcome.FAILED else Outcome.DONE
    }

    fun timeoutNotice(modelName: String): String =
        "$modelName：预热 ${TIMEOUT_MS / 1000} 秒未完成，已跳过（首次回复可能较慢；若一直没有回复请改用 CPU 后端）"
}
