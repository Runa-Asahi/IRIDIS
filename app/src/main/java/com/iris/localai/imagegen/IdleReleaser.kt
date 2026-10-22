package com.iris.localai.imagegen

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Runs an action after a delay; tests use a fake clock. */
fun interface DelayScheduler {
    /** Schedules [action] in [delayMs]; the returned handle cancels it (no-op once it ran). */
    fun schedule(delayMs: Long, action: () -> Unit): Cancellable

    fun interface Cancellable { fun cancel() }

    companion object {
        /** One daemon thread, shared by every [IdleReleaser]: releasing NPU contexts must not run on the main thread. */
        val DEFAULT: DelayScheduler by lazy {
            val exec: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "imagegen-idle").apply { isDaemon = true }
            }
            DelayScheduler { delayMs, action ->
                val f = exec.schedule({ runCatching(action) }, delayMs, TimeUnit.MILLISECONDS)
                DelayScheduler.Cancellable { f.cancel(false) }
            }
        }
    }
}

/**
 * Frees the 生图 NPU graphs (≈1.1 GB) once nothing has used them for [timeoutMs]:
 * every generation (生图 page or the generate_image tool) runs inside [track]; when the last one ends a release
 * is scheduled, and a new generation cancels it. [releaseWhenIdle] (leaving the 生图 page) releases right away
 * (on the scheduler thread), or as soon as the running generation ends.
 */
class IdleReleaser(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val scheduler: DelayScheduler = DelayScheduler.DEFAULT,
    private val release: () -> Unit,
) {
    private val lock = Any()
    private var active = 0
    private var generation = 0L        // bumped by every begin(): a timer armed before it is stale
    private var pending: DelayScheduler.Cancellable? = null
    private var releaseOnIdle = false

    /** Generations running now. */
    val activeCount: Int get() = synchronized(lock) { active }
    /** An idle release is scheduled. */
    val isPending: Boolean get() = synchronized(lock) { pending != null }

    /** A generation starts: cancel any scheduled release. */
    fun begin() = synchronized(lock) {
        active++
        generation++
        pending?.cancel(); pending = null
    }

    /** A generation ended (success, failure or cancel): release now if the page asked, else arm the idle timer. */
    fun end() {
        val now = synchronized(lock) {
            if (active > 0) active--
            if (active > 0) return
            if (releaseOnIdle) { releaseOnIdle = false; true } else { armLocked(timeoutMs); false }
        }
        if (now) doRelease()
    }

    /**
     * Leaving the 生图 page: release now when idle (on the scheduler's thread, never the caller's — closing the
     * contexts can take a while), else right after the running generation.
     */
    fun releaseWhenIdle() = synchronized(lock) {
        if (active > 0) { releaseOnIdle = true; pending?.cancel(); pending = null } else armLocked(0L)
    }

    /** Runs [block] as one generation. */
    suspend fun <T> track(block: suspend () -> T): T {
        begin()
        try { return block() } finally { end() }
    }

    private fun armLocked(delayMs: Long) {
        pending?.cancel()
        val armedFor = generation
        lateinit var handle: DelayScheduler.Cancellable
        handle = scheduler.schedule(delayMs) {
            val go = synchronized(lock) {
                val ok = active == 0 && generation == armedFor && pending === handle
                if (ok) pending = null
                ok
            }
            if (go) doRelease()
        }
        pending = handle
    }

    private fun doRelease() { runCatching(release) }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 60_000L
    }
}
