package com.lagradost.player.impl

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.common.logging.AppLogger
import com.lagradost.player.api.MediaPlayer
import com.lagradost.player.api.PlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class VlcPlayer internal constructor(
    private val remoteControlFactory: VlcRemoteControlFactory,
    private val executableLocator: () -> String? = ::findVlcExecutable,
    private val processLauncher: (List<String>) -> Process = ::startVlcProcess,
) : MediaPlayer {

    constructor() : this(VlcRemoteControlFactory { port -> VlcRcSession(port) })

    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val playerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val playMutex = Mutex()
    private val lifecycleLock = Any()

    // Launch attempts may fail before replacing the current process. Keep their identity
    // separate from the process that currently owns playback state.
    private var launchGeneration = 0L
    private var stopEpoch = 0L
    private var activeProcessGeneration: Long? = null
    private var stdoutJob: Job? = null
    private var remoteControlJob: Job? = null
    private val currentProcess = AtomicReference<Process?>(null)
    private val currentRemoteControl = AtomicReference<VlcRemoteControl?>(null)
    private val activeProxySessionId = AtomicReference<String?>(null)

    private fun killCurrent() {
        synchronized(lifecycleLock) {
            stopEpoch += 1L
            launchGeneration += 1L
            killCurrentLocked()
        }
    }

    /** Caller holds [lifecycleLock]. */
    private fun killCurrentLocked(preserveLoading: Boolean = false) {
        activeProcessGeneration = null
        remoteControlJob?.cancel()
        remoteControlJob = null
        currentRemoteControl.getAndSet(null)?.let { control ->
            try {
                control.close()
            } catch (_: Exception) {
            }
        }
        currentProcess.getAndSet(null)?.let { proc ->
            try {
                proc.destroyForcibly()
                AppLogger.i("VlcPlayer: Killed previous VLC process.")
            } catch (_: Exception) {}
        }
        activeProxySessionId.getAndSet(null)?.let(com.lagradost.player.impl.proxy.LocalStreamProxy::unregisterSession)
        stdoutJob?.cancel()
        stdoutJob = null
        _state.update {
            it.copy(
                isPlaying = false,
                isPaused = false,
                position = 0,
                duration = 0,
                isFinished = false,
                isLoading = preserveLoading && it.isLoading,
                currentUrl = null,
            )
        }
    }

    private fun updateStateForLaunchGeneration(generation: Long, transform: (PlayerState) -> PlayerState) {
        synchronized(lifecycleLock) {
            if (launchGeneration == generation) {
                _state.update(transform)
            }
        }
    }

    private fun updateStateForActiveProcess(generation: Long, transform: (PlayerState) -> PlayerState) {
        synchronized(lifecycleLock) {
            if (activeProcessGeneration == generation) {
                _state.update(transform)
            }
        }
    }

    override suspend fun play(link: ExtractorLink, title: String?, subtitles: List<String>, startPositionMs: Long): Result<Unit> {
        val requestedStopEpoch = synchronized(lifecycleLock) { stopEpoch }
        playMutex.lock()
        try {
            return withContext(Dispatchers.IO) {
                var pendingProxySessionId: String? = null
                var launchedProxySessionId: String? = null
                var launchedProcess: Process? = null
                var launchedRemoteControl: VlcRemoteControl? = null
                var attemptGeneration = 0L
                var playbackWasReplaced = false
                try {
                    val canStart = synchronized(lifecycleLock) {
                        if (stopEpoch != requestedStopEpoch) {
                            false
                        } else {
                            attemptGeneration = ++launchGeneration
                            _state.update { state -> state.copy(isLoading = true, error = null) }
                            true
                        }
                    }
                    if (!canStart) {
                        return@withContext Result.failure(
                            kotlinx.coroutines.CancellationException("VLC playback was stopped while waiting to launch"),
                        )
                    }

                    val vlcExecutable = executableLocator()
                        ?: run {
                            updateStateForLaunchGeneration(attemptGeneration) { state ->
                                state.copy(isLoading = false, error = "VLC not found")
                            }
                            return@withContext Result.failure(IllegalStateException("VLC not found. Install VLC or use MPV."))
                        }

                    val validated = PlayerLinkHandler.validate(link, title).getOrElse {
                        updateStateForLaunchGeneration(attemptGeneration) { state ->
                            state.copy(isLoading = false, error = it.message)
                        }
                        return@withContext Result.failure(it)
                    }
                    pendingProxySessionId = validated.proxySessionId

                    val startSec = startPositionMs / 1000L
                    val rcPort = allocateVlcRcPort()
                    val isWindows = System.getProperty("os.name").lowercase().contains("win")

                    val args = mutableListOf(vlcExecutable)
                    // Keep VLC in its own process and give this launch a private, loopback-only RC endpoint.
                    // This prevents single-instance VLC from forwarding the URL to an unrelated player.
                    args.add("--no-one-instance")
                    // VLC exposes the oldrc module as "rc" on Windows and "oldrc" elsewhere.
                    args.add("--extraintf=${if (isWindows) "rc" else "oldrc"}")
                    args.add("--rc-host=127.0.0.1:$rcPort")
                    args.add("--no-rc-show-pos")
                    if (isWindows) {
                        args.add("--rc-quiet")
                    } else {
                        // On Unix-like systems VLC otherwise refuses the RC module when stdin is not a TTY.
                        args.add("--rc-fake-tty")
                    }

                    val userAgent = validated.headers.entries.firstOrNull { it.key.equals("user-agent", true) }?.value
                    if (!userAgent.isNullOrBlank()) {
                        args.add("--http-user-agent=$userAgent")
                    }

                    val referer = validated.headers.entries.firstOrNull {
                        it.key.equals("referer", true) || it.key.equals("referrer", true)
                    }?.value
                    if (!referer.isNullOrBlank()) {
                        args.add("--http-referrer=$referer")
                    }

                    subtitles.filter { it.isNotBlank() }.forEach { sub ->
                        args.add("--sub-file=$sub")
                    }

                    if (startSec > 0) {
                        args.add("--start-time=$startSec")
                    }

                    // Force VLC to display the correct title instead of raw URL strings
                    args.add("--meta-title=${validated.displayTitle}")
                    args.add("--video-title=${validated.displayTitle}")

                    if (validated.useUrlFile) {
                        val listFile = PlayerLinkHandler.writeUrlListFile(
                            "cloudstream_vlc_url_",
                            validated.displayTitle,
                            validated.url,
                        )
                        args.add(listFile.absolutePath)
                    } else {
                        args.add(validated.url)
                    }

                    AppLogger.i("Launching VLC (${validated.streamKind}): ${validated.displayTitle}")

                    val readyToLaunch = synchronized(lifecycleLock) {
                        if (launchGeneration != attemptGeneration) {
                            false
                        } else {
                            killCurrentLocked(preserveLoading = true)
                            playbackWasReplaced = true
                            activeProxySessionId.set(pendingProxySessionId)
                            launchedProxySessionId = pendingProxySessionId
                            pendingProxySessionId = null
                            true
                        }
                    }
                    if (!readyToLaunch) {
                        pendingProxySessionId?.let(com.lagradost.player.impl.proxy.LocalStreamProxy::unregisterSession)
                        pendingProxySessionId = null
                        return@withContext Result.failure(
                            kotlinx.coroutines.CancellationException("VLC playback was stopped before launch"),
                        )
                    }

                    val process = processLauncher(args)
                    launchedProcess = process

                    val remoteControl = remoteControlFactory.create(rcPort)
                    launchedRemoteControl = remoteControl
                    val committed = synchronized(lifecycleLock) {
                        if (launchGeneration != attemptGeneration) {
                            false
                        } else {
                            currentProcess.set(process)
                            currentRemoteControl.set(remoteControl)
                            activeProcessGeneration = attemptGeneration
                            _state.update {
                                it.copy(
                                    isPlaying = true,
                                    isPaused = false,
                                    isLoading = false,
                                    isFinished = false,
                                    position = startPositionMs.coerceAtLeast(0L),
                                    duration = 0L,
                                    currentUrl = validated.url,
                                )
                            }
                            true
                        }
                    }
                    if (!committed) {
                        throw kotlinx.coroutines.CancellationException("VLC playback was stopped before launch commit")
                    }

                    remoteControlJob = playerScope.launch(Dispatchers.IO) {
                        try {
                            while (currentProcess.get() === process && kotlinx.coroutines.currentCoroutineContext().isActive) {
                                if (!remoteControl.connect(REMOTE_CONNECT_TIMEOUT_MS)) {
                                    kotlinx.coroutines.delay(REMOTE_RETRY_DELAY_MS)
                                    continue
                                }

                                val snapshot = remoteControl.readPlaybackSnapshot()
                                if (currentProcess.get() !== process) break
                                if (snapshot != null) {
                                    _state.update { state ->
                                        if (currentProcess.get() !== process || currentRemoteControl.get() !== remoteControl) {
                                            state
                                        } else {
                                            state.copy(
                                                position = snapshot.positionMs ?: state.position,
                                                duration = snapshot.durationMs ?: state.duration,
                                            )
                                        }
                                    }
                                }
                                kotlinx.coroutines.delay(REMOTE_POLL_INTERVAL_MS)
                            }
                        } catch (_: kotlinx.coroutines.CancellationException) {
                        } catch (e: Exception) {
                            AppLogger.i("VLC RC polling ended: ${e.message}")
                        } finally {
                            if (currentRemoteControl.compareAndSet(remoteControl, null)) {
                                try {
                                    remoteControl.close()
                                } catch (_: Exception) {
                                }
                            }
                        }
                    }

                    stdoutJob = playerScope.launch(Dispatchers.IO) {
                        try {
                            process.inputStream.bufferedReader().use { reader ->
                                reader.lineSequence().forEach { AppLogger.i("VLC: $it") }
                            }
                            process.waitFor()
                        } catch (e: Exception) {
                            AppLogger.i("VLC process ended: ${e.message}")
                        } finally {
                            if (currentProcess.compareAndSet(process, null)) {
                                if (currentRemoteControl.compareAndSet(remoteControl, null)) {
                                    try {
                                        remoteControl.close()
                                    } catch (_: Exception) {
                                    }
                                }
                                validated.proxySessionId?.let { sessionId ->
                                    if (activeProxySessionId.compareAndSet(sessionId, null)) {
                                        com.lagradost.player.impl.proxy.LocalStreamProxy.unregisterSession(sessionId)
                                    }
                                }
                                updateStateForActiveProcess(attemptGeneration) { state ->
                                    state.copy(isPlaying = false, isPaused = false, isFinished = true)
                                }
                            }
                        }
                    }

                    Result.success(Unit)
                } catch (e: Exception) {
                    pendingProxySessionId?.let(com.lagradost.player.impl.proxy.LocalStreamProxy::unregisterSession)
                    launchedProxySessionId?.let { sessionId ->
                        if (activeProxySessionId.compareAndSet(sessionId, null)) {
                            com.lagradost.player.impl.proxy.LocalStreamProxy.unregisterSession(sessionId)
                        }
                    }
                    launchedRemoteControl?.let { control ->
                        currentRemoteControl.compareAndSet(control, null)
                        try {
                            control.close()
                        } catch (_: Exception) {
                        }
                    }
                    launchedProcess?.let { process ->
                        currentProcess.compareAndSet(process, null)
                        try {
                            process.destroyForcibly()
                        } catch (_: Exception) {
                        }
                    }
                    AppLogger.i("VLC launch failed: ${e.message}")
                    updateStateForLaunchGeneration(attemptGeneration) { state ->
                        if (playbackWasReplaced) {
                            state.copy(
                                isPlaying = false,
                                isPaused = false,
                                isLoading = false,
                                isFinished = false,
                                position = 0L,
                                duration = 0L,
                                currentUrl = null,
                                error = e.message,
                            )
                        } else {
                            // Keep a still-running playback intact when this attempt fails before
                            // the replacement point (for example, while building launch arguments).
                            state.copy(isLoading = false, error = e.message)
                        }
                    }
                    Result.failure(e)
                }
            }
        } finally {
            playMutex.unlock()
        }
    }

    override fun playLocal(file: File) {
        playerScope.launch {
            play(
                link = com.lagradost.cloudstream3.utils.newExtractorLink(
                    source = "Local",
                    name = "Local File",
                    url = file.absolutePath,
                ),
                title = file.name,
                subtitles = emptyList(),
                startPositionMs = 0,
            )
        }
    }

    override fun pause() {
        val process = currentProcess.get() ?: return
        val control = currentRemoteControl.get() ?: return
        while (true) {
            val state = _state.value
            if (!state.isPlaying || state.isPaused) return
            if (_state.compareAndSet(state, state.copy(isPaused = true))) break
        }
        playerScope.launch(Dispatchers.IO) {
            if (currentProcess.get() !== process || currentRemoteControl.get() !== control) return@launch
            if (!control.pause()) {
                _state.update { state ->
                    if (currentProcess.get() !== process || currentRemoteControl.get() !== control) {
                        state
                    } else if (state.isPaused) {
                        state.copy(isPaused = false)
                    } else {
                        state
                    }
                }
            }
        }
    }

    override fun resume() {
        val process = currentProcess.get() ?: return
        val control = currentRemoteControl.get() ?: return
        while (true) {
            val state = _state.value
            if (!state.isPlaying || !state.isPaused) return
            if (_state.compareAndSet(state, state.copy(isPaused = false))) break
        }
        playerScope.launch(Dispatchers.IO) {
            if (currentProcess.get() !== process || currentRemoteControl.get() !== control) return@launch
            if (!control.resume()) {
                _state.update { state ->
                    if (currentProcess.get() !== process || currentRemoteControl.get() !== control) {
                        state
                    } else if (!state.isPaused) {
                        state.copy(isPaused = true)
                    } else {
                        state
                    }
                }
            }
        }
    }

    override fun seek(positionMs: Long) {
        val process = currentProcess.get() ?: return
        val control = currentRemoteControl.get() ?: return
        playerScope.launch(Dispatchers.IO) {
            if (currentProcess.get() === process && currentRemoteControl.get() === control) {
                control.seek(positionMs)
            }
        }
    }

    override fun stop() {
        killCurrent()
    }

    override fun destroy() {
        killCurrent()
    }

    private companion object {
        const val REMOTE_CONNECT_TIMEOUT_MS = 1_000L
        const val REMOTE_RETRY_DELAY_MS = 300L
        const val REMOTE_POLL_INTERVAL_MS = 500L
    }
}

private fun findVlcExecutable(): String? {
    try {
        fun probeCommand(vararg cmd: String): String? {
            return try {
                val process = ProcessBuilder(cmd.toList())
                    .redirectErrorStream(true)
                    .start()
                process.inputStream.bufferedReader().use { it.readLine()?.trim()?.takeIf { it.isNotBlank() } }
            } catch (_: Exception) {
                null
            }
        }

        val os = System.getProperty("os.name").lowercase()
        if (os.contains("win")) {
            probeCommand("where", "vlc")?.let {
                if (File(it).exists()) return it
            }
            val bases = listOfNotNull(
                System.getenv("ProgramFiles"),
                System.getenv("ProgramFiles(x86)"),
                System.getenv("ProgramW6432"),
            )
            for (base in bases) {
                val path = File(base, "VideoLAN\\VLC\\vlc.exe")
                if (path.exists()) return path.absolutePath
            }
        } else {
            probeCommand("which", "vlc")?.let {
                if (File(it).exists()) return it
            }
            listOf(
                "/Applications/VLC.app/Contents/MacOS/VLC",
                "/usr/local/bin/vlc",
                "/opt/homebrew/bin/vlc",
            ).forEach { if (File(it).exists()) return it }
        }
    } catch (_: Exception) {
    }
    return null
}

private fun startVlcProcess(args: List<String>): Process = ProcessBuilder(args)
    .redirectErrorStream(true)
    .start()
