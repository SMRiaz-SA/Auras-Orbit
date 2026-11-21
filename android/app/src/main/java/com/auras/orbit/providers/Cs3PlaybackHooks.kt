package com.auras.orbit.providers

import com.auras.orbit.downloads.DownloadProviderHookToken
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.lang.ref.WeakReference
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Ephemeral per-link CS3 hooks. A request or active download owns a fork; live owners are never
 * serialized. Only a bounded, encrypted data token can outlive the process. The provider
 * coordinator revokes each fork on profile/plugin transitions and waits for active
 * interceptor/verifier calls to leave their provider boundary before cleanup continues.
 */
internal class Cs3PlaybackHooks(
    private val pluginId: String,
    private val profileId: String,
    private val providerId: String,
    private val registrationIndex: Int,
    private val apiName: String,
    private val pluginArchiveSha256: String,
    provider: MainAPI,
    videoInterceptorLink: ExtractorLink?,
    extractorData: String?,
    stillCurrent: () -> Boolean,
    private val registrationCoordinator: ProviderOperationCoordinator,
) : AutoCloseable {
    private val apiClassName: String = provider.javaClass.name
    private val lock = Any()
    private val closed = AtomicBoolean(false)
    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(scopeJob + Dispatchers.IO)
    private val hasVideoInterceptor = videoInterceptorLink != null
    private val hasExtractorVerifierData = extractorData != null
    private val videoInterceptorHandles = CopyOnWriteArrayList<AtomicReference<Interceptor?>>()
    private val revocationListener = AtomicReference<Runnable?>(null)

    @Volatile private var provider: MainAPI? = provider
    @Volatile private var videoInterceptorLink: ExtractorLink? = videoInterceptorLink
    @Volatile private var extractorData: String? = extractorData
    @Volatile private var stillCurrent: (() -> Boolean)? = stillCurrent
    @Volatile private var coordinator: ProviderOperationCoordinator? = null
    @Volatile private var registration: ProviderOperationCoordinator.ProviderResource? = null
    @Volatile private var verifierJob: Job? = null
    @Volatile private var activeOwner = false

    init {
        val owner = WeakReference(this)
        registration = registrationCoordinator.registerWeakProviderResource(
            pluginId, profileId, Runnable { owner.get()?.close() },
        )
        if (!isStillCurrent()) close()
    }

    /** A distinct, independently cancellable owner for one playback or download use. */
    fun fork(): Cs3PlaybackHooks = synchronized(lock) {
        val currentProvider = provider
            ?: throw CancellationException("The CS3 provider playback hook is no longer active")
        val currentStillCurrent = stillCurrent
            ?: throw CancellationException("The CS3 provider playback hook is no longer active")
        if (!currentStillCurrent()) {
            throw CancellationException("The CS3 provider playback hook is no longer active")
        }
        Cs3PlaybackHooks(pluginId, profileId, providerId, registrationIndex, apiName,
            pluginArchiveSha256, currentProvider, videoInterceptorLink,
            extractorData, currentStillCurrent, registrationCoordinator)
    }

    /**
     * Produces data-only recovery metadata for the exact encrypted queue URL. The URL itself and
     * the live provider object are never copied into the token.
     */
    fun toDownloadProviderHookToken(sourceId: String, sourceUrl: String): DownloadProviderHookToken? {
        val data = synchronized(lock) {
            if (closed.get() || !activeOwner || !isStillCurrent()) {
                throw CancellationException("The CS3 provider download hook is no longer active")
            }
            extractorData
        } ?: return null
        if (sourceUrl.isBlank() || sourceUrl.length > 65_536) {
            throw IllegalArgumentException("Invalid download source identity.")
        }
        val sourceBytes = sourceUrl.toByteArray(StandardCharsets.UTF_8)
        val sourceDigestBytes = try {
            MessageDigest.getInstance("SHA-256").digest(sourceBytes)
        } finally {
            sourceBytes.fill(0)
        }
        val sourceDigest = try {
            sourceDigestBytes.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        } finally {
            sourceDigestBytes.fill(0)
        }
        return DownloadProviderHookToken(DownloadProviderHookToken.CURRENT_VERSION,
            profileId, pluginId, providerId, registrationIndex, apiClassName, apiName,
            pluginArchiveSha256, sourceId, sourceDigest, data)
    }

    /** True only while this link owner is registered with the coordinator and current. */
    fun isActiveOwner(): Boolean = synchronized(lock) {
        activeOwner && !closed.get() && isStillCurrent()
    }

    /** Exposes terminal ownership state so the queue can replace revoked retry sessions. */
    fun isClosed(): Boolean = closed.get()

    /** True when this owner was created with bounded extractor verifier data. */
    fun hasVerifierData(): Boolean = hasExtractorVerifierData

    /** True only for a current interceptor owner that has no verifier data to execute. */
    fun isInterceptorOnlyOwner(): Boolean = hasVideoInterceptor
        && !hasExtractorVerifierData && isActiveOwner()

    /** Confirms the cancellation owner is scoped to the request's profile. */
    fun isInterceptorOnlyOwnerForProfile(expectedProfileId: String): Boolean =
        profileId == expectedProfileId && isInterceptorOnlyOwner()

    /** Installs one download-task callback so revocation also stops its direct transfer. */
    fun setRevocationListener(listener: Runnable?) {
        if (listener == null) {
            revocationListener.set(null)
            return
        }
        if (closed.get()) {
            listener.run()
            return
        }
        revocationListener.set(listener)
        if (closed.get()) revocationListener.getAndSet(null)?.run()
    }

    /** Register ownership before handing the link to Orbit's player or download queue. */
    fun activate(operationCoordinator: ProviderOperationCoordinator) {
        synchronized(lock) {
            if (closed.get() || !isStillCurrent()) {
                throw CancellationException("The CS3 provider link is no longer active")
            }
            if (operationCoordinator !== registrationCoordinator) {
                throw IllegalArgumentException("CS3 provider hook belongs to a different coordinator")
            }
            if (activeOwner) return
            registration?.close()
            coordinator = operationCoordinator
            registration = operationCoordinator.registerProviderResource(
                pluginId, profileId, Runnable { close() },
            )
            activeOwner = true
            if (!isStillCurrent()) {
                close()
                throw CancellationException("The CS3 provider link is no longer active")
            }
        }
    }

    /**
     * Return an Orbit-gated interceptor wrapper. The provider object stays in this revocable owner;
     * requests begun after its profile/plugin scope closes fail closed without retaining the plugin.
     */
    fun videoInterceptor(): Interceptor? {
        if (!hasVideoInterceptor) return null
        if (closed.get()) throw IOException("The provider playback hook is no longer active.")
        val operationCoordinator = coordinator
            ?: throw IOException("The provider playback hook is not active.")
        if (!isStillCurrent()) throw IOException("The provider playback hook is no longer active.")
        val token = try {
            operationCoordinator.beginOwnedProviderOperation(pluginId, profileId)
        } catch (_: CancellationException) {
            throw IOException("The provider playback hook is no longer active.")
        }
        val delegate = try {
            if (closed.get() || !isStillCurrent()) {
                throw IOException("The provider playback hook is no longer active.")
            }
            val currentProvider = provider
                ?: throw IOException("The provider playback hook is no longer active.")
            val link = videoInterceptorLink
                ?: throw IOException("The provider playback hook is no longer active.")
            currentProvider.getVideoInterceptor(link)
        } finally {
            token.close()
        } ?: return null

        val handle = AtomicReference<Interceptor?>(delegate)
        synchronized(lock) {
            if (closed.get() || !isStillCurrent()) {
                handle.set(null)
                throw IOException("The provider playback hook is no longer active.")
            }
            videoInterceptorHandles.add(handle)
        }
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                if (closed.get()) throw IOException("The provider playback hook is no longer active.")
                val operationCoordinator = coordinator
                    ?: throw IOException("The provider playback hook is not active.")
                if (!isStillCurrent()) throw IOException("The provider playback hook is no longer active.")
                val token = try {
                    operationCoordinator.beginOwnedProviderOperation(pluginId, profileId)
                } catch (_: CancellationException) {
                    throw IOException("The provider playback hook is no longer active.")
                }
                return try {
                    if (closed.get() || !isStillCurrent()) {
                        throw IOException("The provider playback hook is no longer active.")
                    }
                    val delegate = handle.get()
                        ?: throw IOException("The provider playback hook is no longer active.")
                    delegate.intercept(chain)
                } finally {
                    token.close()
                }
            }
        }
    }

    /** Start the optional CS3 keep-alive job for this selected playback/download link. */
    fun startVerifierJob() {
        val data = extractorData ?: return
        cancelVerifierJob()
        val operationCoordinator = coordinator ?: return
        val currentProvider = provider ?: return
        if (closed.get() || !isStillCurrent()) return
        val token = try {
            operationCoordinator.beginOwnedProviderOperation(pluginId, profileId)
        } catch (_: CancellationException) {
            return
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                if (!closed.get() && isStillCurrent()) {
                    invokeExtractorVerifierJob(currentProvider, data)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // A verifier refresh is ancillary; a provider failure must not stop media playback.
            }
        }
        job.invokeOnCompletion {
            token.close()
            synchronized(lock) {
                if (verifierJob === job) verifierJob = null
            }
        }
        synchronized(lock) {
            if (closed.get() || !isStillCurrent() || provider !== currentProvider) {
                job.cancel()
            } else {
                verifierJob = job
                job.start()
            }
        }
    }

    fun cancelVerifierJob() {
        val current = synchronized(lock) {
            val job = verifierJob
            verifierJob = null
            job
        }
        current?.cancel()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val (currentJob, currentRegistration) = synchronized(lock) {
            val job = verifierJob
            verifierJob = null
            val ownerRegistration = registration
            registration = null
            activeOwner = false
            coordinator = null
            provider = null
            videoInterceptorLink = null
            extractorData = null
            stillCurrent = null
            for (handle in videoInterceptorHandles) handle.set(null)
            videoInterceptorHandles.clear()
            job to ownerRegistration
        }
        currentJob?.cancel()
        scope.cancel()
        currentRegistration?.close()
        revocationListener.getAndSet(null)?.run()
    }

    private fun isStillCurrent(): Boolean = try {
        stillCurrent?.invoke() == true
    } catch (_: Throwable) {
        false
    }

    /** Bridges MainAPI's Java suspend ABI to this cancellable coroutine. */
    private suspend fun invokeExtractorVerifierJob(currentProvider: MainAPI, data: String) {
        suspendCancellableCoroutine<Unit> { continuation ->
            if (!continuation.isActive) return@suspendCancellableCoroutine
            try {
                val result = currentProvider.extractorVerifierJob(data, continuation)
                if (result !== COROUTINE_SUSPENDED) continuation.resume(result as Unit)
            } catch (failure: Throwable) {
                continuation.resumeWithException(failure)
            }
        }
    }
}
