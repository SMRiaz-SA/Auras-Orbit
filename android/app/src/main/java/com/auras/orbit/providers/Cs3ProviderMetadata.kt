package com.auras.orbit.providers

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.function.BooleanSupplier

/** Immutable, generation-scoped metadata read away from UI and provider-operation workers. */
class Cs3ProviderMetadata {
    /** Registration equality uses MainAPI object identity, never plugin-overridable equals/hashCode. */
    class Key(
        val profileId: String,
        val pluginId: String,
        val profileGeneration: Long,
        val pluginGeneration: Long,
        val registrationIndex: Int,
        private val api: MainAPI,
    ) {
        fun provider(): MainAPI = api

        override fun equals(other: Any?): Boolean = other is Key
            && profileId == other.profileId
            && pluginId == other.pluginId
            && profileGeneration == other.profileGeneration
            && pluginGeneration == other.pluginGeneration
            && registrationIndex == other.registrationIndex
            && api === other.api

        override fun hashCode(): Int {
            var result = profileId.hashCode()
            result = 31 * result + pluginId.hashCode()
            result = 31 * result + profileGeneration.hashCode()
            result = 31 * result + pluginGeneration.hashCode()
            result = 31 * result + registrationIndex
            result = 31 * result + System.identityHashCode(api)
            return result
        }
    }

    data class Snapshot(
        val key: Key,
        val name: String,
        val hasMainPage: Boolean,
        val hasQuickSearch: Boolean,
        val supportedTypes: Set<TvType>,
    )

    enum class AwaitStatus { READY, TIMED_OUT, UNAVAILABLE }

    data class AwaitResult(val status: AwaitStatus, val snapshot: Snapshot?)

    private class RegistrationIdentity(
        val pluginId: String,
        val registrationIndex: Int,
        private val api: MainAPI,
    ) {
        override fun equals(other: Any?): Boolean = other is RegistrationIdentity
            && pluginId == other.pluginId
            && registrationIndex == other.registrationIndex
            && api === other.api

        override fun hashCode(): Int {
            var result = 31 * pluginId.hashCode() + registrationIndex
            result = 31 * result + System.identityHashCode(api)
            return result
        }
    }

    private class ReadRequest(
        val key: Key,
        val identity: RegistrationIdentity,
        val fallbackName: String,
        val beginLease: BooleanSupplier,
        val endLease: Runnable,
        val stillCurrent: BooleanSupplier,
        val onPublished: Runnable,
    ) {
        val task = FutureTask<Snapshot?> {
            if (!stillCurrent.asBoolean || !beginLease.asBoolean) {
                null
            } else {
                val snapshot = try {
                    readProperties(key, fallbackName)
                } finally {
                    // Wait timeout/interruption never cancels a getter or releases its lease early.
                    endLease.run()
                }
                if (stillCurrent.asBoolean) snapshot else null
            }
        }

        private fun readProperties(key: Key, fallbackName: String): Snapshot {
            val api = key.provider()
            val name = try {
                // Bound untrusted metadata before trimming: String.trim() otherwise copies
                // a provider-controlled string of arbitrary length on this worker.
                api.name?.take(160)?.trim()?.takeIf { it.isNotEmpty() && it != "NONE" }
                    ?: fallbackName.take(160).trim()
            } catch (_: Throwable) {
                fallbackName
            }
            val hasMainPage = try {
                api.hasMainPage
            } catch (_: Throwable) {
                false
            }
            val hasQuickSearch = try {
                api.hasQuickSearch
            } catch (_: Throwable) {
                false
            }
            val supportedTypes = try {
                val copy = LinkedHashSet<TvType>()
                val supplied = api.supportedTypes
                // Provider implementations may return a custom Set with an iterator
                // that never terminates. Never scan unbounded plugin-owned metadata.
                if (supplied != null) {
                    val entries = supplied.iterator()
                    var scanned = 0
                    while (scanned < 128 && entries.hasNext()) {
                        copy.add(entries.next())
                        scanned++
                    }
                }
                Collections.unmodifiableSet(copy)
            } catch (_: Throwable) {
                emptySet()
            }
            return Snapshot(key, name, hasMainPage, hasQuickSearch, supportedTypes)
        }
    }

    private class WorkerState(val identity: RegistrationIdentity) {
        var current: ReadRequest? = null
        var pending: ReadRequest? = null
    }

    private val monitor = Any()
    /** Completed immutable snapshots and wait handles are indexed by generation-specific keys. */
    private val requests = ConcurrentHashMap<Key, FutureTask<Snapshot?>>()
    /** One serial daemon worker per live API registration, shared across profile generations. */
    private val workers = HashMap<RegistrationIdentity, WorkerState>()

    fun cached(key: Key): Snapshot? {
        val task = requests[key] ?: return null
        if (!task.isDone || task.isCancelled) return null
        return try {
            task.get()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: ExecutionException) {
            null
        } catch (_: CancellationException) {
            null
        }
    }

    /** Starts at most one read for each generation key and serializes generations per registration. */
    fun request(
        key: Key,
        fallbackName: String,
        beginLease: BooleanSupplier,
        endLease: Runnable,
        stillCurrent: BooleanSupplier,
        onPublished: Runnable,
    ) {
        val identity = RegistrationIdentity(key.pluginId, key.registrationIndex, key.provider())
        val request = ReadRequest(key, identity, fallbackName, beginLease, endLease,
            stillCurrent, onPublished)
        var newWorker: WorkerState? = null
        synchronized(monitor) {
            if (requests.containsKey(key)) return
            requests[key] = request.task
            val state = workers[identity]
            if (state == null) {
                val created = WorkerState(identity)
                created.pending = request
                workers[identity] = created
                newWorker = created
            } else {
                // Keep only the newest pending generation. The active getter always runs to exit.
                state.pending?.let { superseded ->
                    requests.remove(superseded.key, superseded.task)
                    superseded.task.cancel(false)
                }
                state.pending = request
            }
        }
        newWorker?.let(::startWorker)
    }

    private fun startWorker(state: WorkerState) {
        try {
            Thread({ process(state) },
                "orbit-cs3-provider-metadata-${Integer.toHexString(state.identity.hashCode())}")
                .apply { isDaemon = true }
                .start()
        } catch (_: RuntimeException) {
            synchronized(monitor) {
                if (workers[state.identity] === state) {
                    state.pending?.let { pending ->
                        requests.remove(pending.key, pending.task)
                        pending.task.cancel(false)
                    }
                    workers.remove(state.identity)
                }
            }
        }
    }

    private fun process(state: WorkerState) {
        while (true) {
            val request = synchronized(monitor) {
                val next = state.pending
                state.pending = null
                if (next == null) {
                    if (workers[state.identity] === state) workers.remove(state.identity)
                    null
                } else {
                    state.current = next
                    next
                }
            } ?: return

            request.task.run()
            val snapshot = try {
                request.task.get()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                null
            } catch (_: ExecutionException) {
                null
            } catch (_: CancellationException) {
                null
            }
            // A lifecycle supplier can throw during a profile switch. Treat failure
            // as invalidation, never let it kill the serial worker with pending work.
            val remainsCurrent = if (snapshot == null) false else try {
                request.stillCurrent.asBoolean
            } catch (_: Throwable) {
                false
            }
            if (!remainsCurrent) {
                requests.remove(request.key, request.task)
            } else {
                try {
                    request.onPublished.run()
                } catch (_: Throwable) {
                    // Notification failure must not strand this registration's next generation.
                }
            }

            synchronized(monitor) {
                state.current = null
                if (state.pending == null) {
                    if (workers[state.identity] === state) workers.remove(state.identity)
                    return
                }
            }
        }
    }

    /** Waits without cancelling the in-flight getter when the caller times out or is interrupted. */
    @Throws(InterruptedException::class)
    fun await(key: Key, timeoutMs: Long): AwaitResult {
        val task = requests[key] ?: return AwaitResult(AwaitStatus.UNAVAILABLE, null)
        return try {
            val snapshot = task.get(timeoutMs, TimeUnit.MILLISECONDS)
            if (snapshot == null) AwaitResult(AwaitStatus.UNAVAILABLE, null)
            else AwaitResult(AwaitStatus.READY, snapshot)
        } catch (_: TimeoutException) {
            AwaitResult(AwaitStatus.TIMED_OUT, null)
        } catch (_: ExecutionException) {
            AwaitResult(AwaitStatus.UNAVAILABLE, null)
        } catch (_: CancellationException) {
            AwaitResult(AwaitStatus.UNAVAILABLE, null)
        }
    }

    /** Removes cache/pending work without interrupting a plugin getter already in progress. */
    fun invalidatePlugin(pluginId: String) {
        synchronized(monitor) {
            for (key in requests.keys) if (key.pluginId == pluginId) requests.remove(key)
            for (state in workers.values) if (state.identity.pluginId == pluginId) {
                state.pending?.let { pending -> pending.task.cancel(false) }
                state.pending = null
            }
        }
    }

    /** Invalidates all profiles; active getters retain their leases and serial worker until exit. */
    fun invalidateAll() {
        synchronized(monitor) {
            requests.clear()
            for (state in workers.values) {
                state.pending?.task?.cancel(false)
                state.pending = null
            }
        }
    }
}
