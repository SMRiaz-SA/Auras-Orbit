package com.auras.orbit.providers

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.auras.orbit.BuildConfig
import com.auras.orbit.player.OrbitPlayerActivity
import com.auras.orbit.player.PlaybackRequest
import com.lagradost.cloudstream3.AudioFile
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Fake-only coverage for CS3 separate audio files; this test never loads a plugin archive. */
@RunWith(AndroidJUnit4::class)
class Cs3AudioTracksInstrumentationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var target: android.content.Context
    private lateinit var testContext: android.content.Context
    private lateinit var fixtureDirectory: File
    private var playerActivity: OrbitPlayerActivity? = null
    private var television = false

    @Before
    fun prepareIsolatedTarget() {
        target = instrumentation.targetContext
        testContext = instrumentation.context
        assertTrue("audio fixture must use an isolated test application package",
            target.packageName.endsWith(".queuerender") || target.packageName.endsWith(".webviewfixture"))
        assertFalse("audio fixture never enables the CS3 canary", BuildConfig.CS3_CANARY_BUILD)
        television = target.packageManager.hasSystemFeature("android.software.leanback")
        fixtureDirectory = File(target.cacheDir, "cs3-audio-fixture").apply { mkdirs() }
    }

    @After
    fun closePlayerAndRemoveMedia() {
        val activity = playerActivity
        if (activity != null && !activityIsDestroyed(activity)) {
            instrumentation.runOnMainSync { activity.finish() }
            val deadline = SystemClock.elapsedRealtime() + 5_000L
            while (!activityIsDestroyed(activity) && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(25L)
            }
        }
        fixtureDirectory.listFiles()?.filterNot { it.name.endsWith(".png") }?.forEach { it.delete() }
    }

    @Test
    fun fakeProviderCallbackAndRequestRoundTripKeepBoundedAudioHeaders() {
        val video = File(fixtureDirectory, "callback-video.mp4").toURI().toASCIIString()
        val externalOne = File(fixtureDirectory, "callback-audio-1.wav").toURI().toASCIIString()
        val externalTwo = File(fixtureDirectory, "callback-audio-2.wav").toURI().toASCIIString()
        val invalid = "https://fixture.invalid/" + "x".repeat(4_100)
        val api = object : MainAPI() {
            override fun getName(): String = "Orbit audio fixture"
            override fun getMainUrl(): String = "https://provider.invalid"
            override fun getSupportedTypes(): Set<TvType> = setOf(TvType.Movie)

            override suspend fun loadLinks(
                data: String,
                isCasting: Boolean,
                subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
                callback: (ExtractorLink) -> Unit,
            ): Boolean {
                val link = ExtractorLink(
                    "fixture", "Fixture video", video, "", 1080,
                    mapOf("X-Video-Fixture" to "video-header"), null,
                    ExtractorLinkType.VIDEO,
                    listOf(
                        AudioFile(externalOne, mapOf("X-Audio-Fixture" to "first-header")),
                        AudioFile(externalTwo, mapOf("X-Audio-Fixture" to "second-header")),
                        AudioFile(invalid, emptyMap()),
                    ),
                )
                callback(link)
                return true
            }
        }

        val provider = Cs3MediaProviderAdapter(
            pluginId = "fixture-plugin", profileId = "fixture-profile", registrationIndex = 0,
            providerId = "audio-fixture", fallbackName = "Audio fixture", api = api,
            metadataKey = Cs3ProviderMetadata.Key(
                profileId = "fixture-profile", pluginId = "fixture-plugin",
                profileGeneration = 0L, pluginGeneration = 0L,
                registrationIndex = 0, api = api,
            ),
            suppliedMetadata = null,
            pluginArchiveSha256 = "0".repeat(64),
            stillCurrent = { true },
        )
        val result = provider.resolveLinks("fixture-item", false)
        assertTrue("fake provider callback returns the video candidate", result.resolved)
        assertEquals("invalid optional audio metadata does not discard its video", 1, result.links.size)
        val candidate = result.links.single()
        assertEquals("valid external audio files are captured", 2, candidate.audioTracks.size)
        assertEquals("audio-specific headers stay attached to their track",
            "first-header", candidate.audioTracks[0].headers["X-Audio-Fixture"])

        val source = PlaybackRequest.Source(
            "fixture-source", "Fixture link", video, "video/mp4",
            mapOf("X-Video-Fixture" to "video-header"), emptyList(),
            candidate.audioTracks.map {
                PlaybackRequest.AudioTrack(it.id, it.uri, it.headers)
            },
        )
        val request = PlaybackRequest(
            "fixture-profile",
            PlaybackRequest.MediaMetadata("fixture-media", "audio-fixture", "Fixture", "Movie", "", "", ""),
            PlaybackRequest.EpisodeMetadata("", "", -1, -1),
            listOf(source), source.id, emptyList(), emptyList(),
        )
        val encoded = invokePrivate(request, "toJson") as String
        val decoded = invokePrivateStatic(PlaybackRequest::class.java, "fromJson", arrayOf(String::class.java), encoded)
                as PlaybackRequest
        assertEquals("request serialization keeps both audio tracks", 2,
            decoded.getDefaultSource().audioTracks.size)
        assertEquals("request serialization keeps per-track headers", "second-header",
            decoded.getDefaultSource().audioTracks[1].headers["X-Audio-Fixture"])
        assertEquals("provider headers remain separate from audio headers", "video-header",
            decoded.getDefaultSource().headers["X-Video-Fixture"])

        val legacyJson = JSONObject(encoded)
        val legacySource = legacyJson.getJSONArray("sources").getJSONObject(0)
        legacySource.remove("audioTracks")
        val legacy = invokePrivateStatic(PlaybackRequest::class.java, "fromJson",
            arrayOf(String::class.java), legacyJson.toString()) as PlaybackRequest
        assertTrue("older playback requests without audioTracks remain valid",
            legacy.getDefaultSource().audioTracks.isEmpty())

        assertTrue("track URIs and headers do not appear in an ordinary display label",
            source.label == "Fixture link")
    }

    @Test
    fun existingAudioDialogSelectsExternalTracksAndFailedTrackFallsBackToVideo() {
        val video = copyAsset("jellyfin-fixture.mp4", "fixture-video.mp4")
        val firstAudio = writeWave("external-audio-one.wav", 440.0)
        val secondAudio = writeWave("external-audio-two.wav", 880.0)
        val missingAudio = File(fixtureDirectory, "missing-audio.wav")
        val tracks = listOf(
            PlaybackRequest.AudioTrack("audio-track-1", firstAudio.toURI().toASCIIString(),
                mapOf("X-Audio-Fixture" to "first")),
            PlaybackRequest.AudioTrack("audio-track-2", secondAudio.toURI().toASCIIString(),
                mapOf("X-Audio-Fixture" to "second")),
            PlaybackRequest.AudioTrack("audio-track-invalid", missingAudio.toURI().toASCIIString(), emptyMap()),
        )
        val source = PlaybackRequest.Source("fixture-source", "Fixture", video.toURI().toASCIIString(),
            "video/mp4", emptyMap(), emptyList(), tracks)
        val request = PlaybackRequest("fixture-profile",
            PlaybackRequest.MediaMetadata("fixture-media", "fixture", "Fixture title", "Movie", "", "", ""),
            PlaybackRequest.EpisodeMetadata("", "", -1, -1), listOf(source), source.id,
            emptyList(), emptyList())
        val launch = request.intent(target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val launched = instrumentation.startActivitySync(launch)
        assertTrue("fixture playback opens Orbit Player", launched is OrbitPlayerActivity)
        playerActivity = launched as OrbitPlayerActivity
        awaitPlayerState(Player.STATE_READY, 20_000L)
        val activity = playerActivity!!
        assertTrue("base video begins playing before optional audio is selected", playerIsPlaying(activity))

        val initialPosition = playerPosition(activity)
        chooseAudioOption(externalAudioIndex = 0)
        awaitExternalAudioSelection("audio-track-1", 20_000L)
        assertSelectedExternalAudioGroup()
        assertTrue("video position continues across external audio selection",
            playerPosition(activity) + 250L >= initialPosition)
        captureAudioDialog("audio-dialog-track-one.png")

        chooseAudioOption(externalAudioIndex = 1)
        awaitExternalAudioSelection("audio-track-2", 20_000L)
        assertSelectedExternalAudioGroup()
        assertTrue("video stays active after switching external audio", playerIsPlaying(activity))
        captureAudioDialog("audio-dialog-track-two.png")

        chooseAutomatic()
        awaitPlayerState(Player.STATE_READY, 20_000L)
        assertEquals("Automatic restores the original video source", "", readActivityField(activity,
            "selectedExternalAudioTrackId"))
        assertNull("Automatic unloads the external audio group", readActivityField(activity,
            "externalAudioTrackGroup"))
        assertTrue("video keeps playing after Automatic restores its own audio", playerIsPlaying(activity))

        val beforeUnavailableTrack = playerPosition(activity)
        chooseAudioOption(externalAudioIndex = 2)
        awaitBaseVideoFallback(20_000L)
        assertTrue("failed external audio does not lose video position",
            playerPosition(activity) + 500L >= beforeUnavailableTrack)
        assertTrue("base video resumes after external audio failure", playerIsPlaying(activity))

        instrumentation.runOnMainSync { activity.finish() }
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        while (!activityIsDestroyed(activity) && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25L)
        assertTrue("player releases its Media3 instance at teardown", readActivityField(activity, "player") == null)
        assertTrue("the player releases external audio files at teardown",
            firstAudio.delete() && secondAudio.delete())
    }

    @Test
    fun queuedItemsDoNotCarryAReusedPositionalExternalAudioTrackId() {
        val firstVideo = copyAsset("jellyfin-fixture.mp4", "queued-first-video.mp4")
        val secondVideo = copyAsset("jellyfin-fixture.mp4", "queued-second-video.mp4")
        val firstAudio = writeWave("queued-first-audio.wav", 440.0)
        val secondAudio = writeWave("queued-second-audio.wav", 880.0)
        val reusedTrackId = "audio-track-0"
        val firstSource = PlaybackRequest.Source("first-source", "First item",
            firstVideo.toURI().toASCIIString(), "video/mp4", emptyMap(), emptyList(),
            listOf(PlaybackRequest.AudioTrack(reusedTrackId, firstAudio.toURI().toASCIIString(), emptyMap())))
        val secondSource = PlaybackRequest.Source("second-source", "Second item",
            secondVideo.toURI().toASCIIString(), "video/mp4", emptyMap(), emptyList(),
            listOf(PlaybackRequest.AudioTrack(reusedTrackId, secondAudio.toURI().toASCIIString(), emptyMap())))
        val secondItem = PlaybackRequest.QueueItem("second-queue-item",
            PlaybackRequest.MediaMetadata("second-media", "fixture-media", "Second title", "Movie", "", "", ""),
            PlaybackRequest.EpisodeMetadata("second-episode", "Episode two", 1, 2),
            listOf(secondSource), secondSource.id, emptyList())
        val request = PlaybackRequest("fixture-profile",
            PlaybackRequest.MediaMetadata("first-media", "fixture-media", "First title", "Movie", "", "", ""),
            PlaybackRequest.EpisodeMetadata("first-episode", "Episode one", 1, 1),
            listOf(firstSource), firstSource.id, emptyList(), listOf(secondItem))
        assertEquals("both queued items intentionally reuse the provider's positional audio id",
            reusedTrackId, secondItem.sources.single().audioTracks.single().id)

        val launched = instrumentation.startActivitySync(
            request.intent(target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue("fixture playback opens Orbit Player", launched is OrbitPlayerActivity)
        playerActivity = launched as OrbitPlayerActivity
        val activity = playerActivity!!
        awaitPlayerState(Player.STATE_READY, 20_000L)
        chooseAudioOption(externalAudioIndex = 0)
        awaitExternalAudioSelection(reusedTrackId, 20_000L)
        assertSelectedExternalAudioGroup()

        instrumentation.runOnMainSync {
            OrbitPlayerActivity::class.java.getDeclaredMethod("onCurrentItemEnded").apply {
                isAccessible = true
                invoke(activity)
            }
        }
        awaitQueuedItemReady("second-media", secondSource.id, 20_000L)
        onMain {
            assertEquals("the next item starts with Automatic audio despite the reused track id", "",
                readField(activity, "selectedExternalAudioTrackId"))
            assertNull("the next item does not retain the first item's external track group",
                readField(activity, "externalAudioTrackGroup"))
            assertFalse("the next item has no pending external track selection",
                readField(activity, "pendingExternalAudioTrackSelection") as Boolean)
            assertFalse("the next item has no pending native track selection",
                readField(activity, "pendingNativeAudioTrackSelection") as Boolean)
        }
        assertTrue("the queued video continues playing", playerIsPlaying(activity))
    }

    private fun chooseAudioOption(externalAudioIndex: Int) {
        val activity = playerActivity ?: error("Player has not started")
        instrumentation.runOnMainSync {
            OrbitPlayerActivity::class.java.getDeclaredMethod("openAudioTracksDialog").apply {
                isAccessible = true
                invoke(activity)
            }
        }
        val dialog = readActivityField(activity, "audioTracksDialog") as AlertDialog
        val choiceCount = onMain {
            (invokePrivate(activity, "getTrackChoices",
                arrayOf(Int::class.javaPrimitiveType!!), C.TRACK_TYPE_AUDIO) as List<*>).size
        }
        val row = 1 + choiceCount + externalAudioIndex
        onMain {
            val list = dialog.listView
            assertTrue("existing Tracks · Audio dialog lists this external track",
                row < list.count && list.adapter.getItem(row).toString() ==
                    "External audio track ${externalAudioIndex + 1}")
        }
        chooseRow(dialog, row)
    }

    private fun chooseAutomatic() {
        val activity = playerActivity ?: error("Player has not started")
        instrumentation.runOnMainSync {
            OrbitPlayerActivity::class.java.getDeclaredMethod("openAudioTracksDialog").apply {
                isAccessible = true
                invoke(activity)
            }
        }
        chooseRow(readActivityField(activity, "audioTracksDialog") as AlertDialog, 0)
    }

    private fun chooseRow(dialog: AlertDialog, row: Int) {
        val list = onMain { dialog.listView }
        if (television) {
            instrumentation.runOnMainSync {
                list.requestFocus()
                list.setSelection(0)
            }
            var selected = onMain { list.selectedItemPosition }
            var guard = 0
            val count = onMain { list.count }
            while (selected < row && guard++ < count + 1) {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                selected = onMain { list.selectedItemPosition }
            }
            assertEquals("TV D-pad reaches the external audio row", row, selected)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            instrumentation.runOnMainSync {
                assertEquals("TV remote selects the requested audio row", row, list.checkedItemPosition)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
        } else {
            instrumentation.runOnMainSync {
                list.performItemClick(list.getChildAt(row - list.firstVisiblePosition), row,
                    list.getItemIdAtPosition(row))
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
        }
    }

    private fun awaitExternalAudioSelection(id: String, timeoutMs: Long) {
        val activity = playerActivity ?: error("Player has not started")
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            val selectedAndReady = onMain {
                val actual = readField(activity, "selectedExternalAudioTrackId") as String
                val pending = readField(activity, "pendingExternalAudioTrackSelection") as Boolean
                val player = readField(activity, "player") as? ExoPlayer
                actual == id && !pending && player?.playbackState == Player.STATE_READY
            }
            if (selectedAndReady) return
            SystemClock.sleep(50L)
        } while (SystemClock.elapsedRealtime() < deadline)
        onMain {
            assertEquals("selected external audio is active", id,
                readField(activity, "selectedExternalAudioTrackId"))
        }
    }

    private fun awaitBaseVideoFallback(timeoutMs: Long) {
        val activity = playerActivity ?: error("Player has not started")
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            val returnedToVideo = onMain {
                val id = readField(activity, "selectedExternalAudioTrackId") as String
                val pending = readField(activity, "pendingExternalAudioTrackSelection") as Boolean
                val player = readField(activity, "player") as? ExoPlayer
                id.isEmpty() && !pending && player?.playbackState == Player.STATE_READY && player.isPlaying
            }
            if (returnedToVideo) return
            SystemClock.sleep(50L)
        } while (SystemClock.elapsedRealtime() < deadline)
        onMain {
            assertEquals("failed external audio clears the selection", "",
                readField(activity, "selectedExternalAudioTrackId"))
            assertEquals("failed external audio returns to a ready video-only source", Player.STATE_READY,
                (readField(activity, "player") as ExoPlayer).playbackState)
        }
    }

    private fun assertSelectedExternalAudioGroup() {
        val activity = playerActivity ?: error("Player has not started")
        onMain {
            val group = readField(activity, "externalAudioTrackGroup") as? androidx.media3.common.Tracks.Group
            assertNotNull("Media3 exposes the selected external audio group", group)
            assertTrue("one supported track from the external audio file is selected",
                group!!.let { current -> (0 until current.length).any { current.isTrackSelected(it) } })
        }
    }

    private fun awaitPlayerState(state: Int, timeoutMs: Long) {
        val activity = playerActivity ?: error("Player has not started")
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            if (onMain { (readField(activity, "player") as? ExoPlayer)?.playbackState == state }) return
            SystemClock.sleep(50L)
        } while (SystemClock.elapsedRealtime() < deadline)
        onMain {
            assertEquals("Media3 reaches requested player state", state,
                (readField(activity, "player") as? ExoPlayer)?.playbackState)
        }
    }

    private fun awaitQueuedItemReady(mediaStableId: String, sourceId: String, timeoutMs: Long) {
        val activity = playerActivity ?: error("Player has not started")
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            val nextItemReady = onMain {
                val request = readField(activity, "request") as? PlaybackRequest
                val source = readField(activity, "currentSource") as? PlaybackRequest.Source
                val player = readField(activity, "player") as? ExoPlayer
                request?.media?.stableId == mediaStableId && source?.id == sourceId
                    && player?.currentMediaItem?.mediaId == sourceId
                    && player.playbackState == Player.STATE_READY
            }
            if (nextItemReady) return
            SystemClock.sleep(50L)
        } while (SystemClock.elapsedRealtime() < deadline)
        onMain {
            val request = readField(activity, "request") as? PlaybackRequest
            val source = readField(activity, "currentSource") as? PlaybackRequest.Source
            assertEquals("queue transition advances to the requested media", mediaStableId,
                request?.media?.stableId)
            assertEquals("queue transition loads its default source", sourceId, source?.id)
            assertEquals("queued video becomes ready", Player.STATE_READY,
                (readField(activity, "player") as? ExoPlayer)?.playbackState)
        }
    }

    private fun playerPosition(activity: Activity): Long =
        onMain { (readField(activity, "player") as ExoPlayer).currentPosition }

    private fun playerIsPlaying(activity: Activity): Boolean =
        onMain { (readField(activity, "player") as ExoPlayer).isPlaying }

    private fun activityIsDestroyed(activity: Activity): Boolean = onMain { activity.isDestroyed }

    private fun readActivityField(activity: Activity, name: String): Any? =
        onMain { readField(activity, name) }

    private fun captureAudioDialog(fileName: String) {
        val activity = playerActivity ?: error("Player has not started")
        if (readActivityField(activity, "audioTracksDialog") == null) {
            instrumentation.runOnMainSync {
                OrbitPlayerActivity::class.java.getDeclaredMethod("openAudioTracksDialog").apply {
                    isAccessible = true
                    invoke(activity)
                }
            }
        }
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull("audio dialog screenshot is available", screenshot)
        val file = File(fixtureDirectory, fileName)
        FileOutputStream(file).use { output ->
            assertTrue("audio dialog screenshot is written", screenshot!!.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        println("CS3_AUDIO_FIXTURE_SCREENSHOT=${file.absolutePath}")
        val dialog = readActivityField(playerActivity!!, "audioTracksDialog") as AlertDialog
        onMain {
            assertTrue("existing player audio dialog keeps its title",
                dialog.window?.decorView?.let { containsText(it, "Tracks · Audio") } == true)
            val list = dialog.listView
            if (television) {
                assertTrue("TV audio selector retains DPAD focus", list.isFocusable && list.isFocusableInTouchMode)
            }
        }
    }

    private fun copyAsset(asset: String, name: String): File {
        val output = File(fixtureDirectory, name)
        testContext.assets.open(asset).use { input -> FileOutputStream(output).use { out -> input.copyTo(out) } }
        return output
    }

    private fun writeWave(name: String, frequency: Double): File {
        val sampleRate = 22_050
        val seconds = 20
        val samples = sampleRate * seconds
        val pcm = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until samples) {
            val sample = (sin(2.0 * Math.PI * frequency * i / sampleRate) * 4_000.0).toInt().toShort()
            pcm.putShort(sample)
        }
        val data = pcm.array()
        val output = ByteArrayOutputStream(44 + data.size)
        output.write("RIFF".toByteArray(Charsets.US_ASCII))
        writeIntLe(output, 36 + data.size)
        output.write("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        writeIntLe(output, 16)
        writeShortLe(output, 1)
        writeShortLe(output, 1)
        writeIntLe(output, sampleRate)
        writeIntLe(output, sampleRate * 2)
        writeShortLe(output, 2)
        writeShortLe(output, 16)
        output.write("data".toByteArray(Charsets.US_ASCII))
        writeIntLe(output, data.size)
        output.write(data)
        val file = File(fixtureDirectory, name)
        FileOutputStream(file).use { it.write(output.toByteArray()) }
        return file
    }

    private fun writeIntLe(output: ByteArrayOutputStream, value: Int) {
        output.write(value and 0xff)
        output.write((value ushr 8) and 0xff)
        output.write((value ushr 16) and 0xff)
        output.write((value ushr 24) and 0xff)
    }

    private fun writeShortLe(output: ByteArrayOutputStream, value: Int) {
        output.write(value and 0xff)
        output.write((value ushr 8) and 0xff)
    }

    private fun containsText(view: View, expected: String): Boolean {
        if (view is TextView && expected.contentEquals(view.text)) return true
        if (view is android.view.ViewGroup) {
            for (index in 0 until view.childCount) if (containsText(view.getChildAt(index), expected)) return true
        }
        return false
    }

    private fun invokePrivate(receiver: Any, name: String, parameterTypes: Array<Class<*>> = emptyArray(),
                              vararg arguments: Any): Any? = invokeMethod(receiver.javaClass, receiver, name,
        parameterTypes, *arguments)

    private fun invokePrivateStatic(type: Class<*>, name: String, parameterTypes: Array<Class<*>>,
                                    vararg arguments: Any): Any? = invokeMethod(type, null, name,
        parameterTypes, *arguments)

    private fun invokeMethod(type: Class<*>, receiver: Any?, name: String,
                             parameterTypes: Array<Class<*>>, vararg arguments: Any): Any? {
        val method: Method = type.getDeclaredMethod(name, *parameterTypes).apply { isAccessible = true }
        return method.invoke(receiver, *arguments)
    }

    private fun readField(receiver: Any, name: String): Any? {
        var type: Class<*>? = receiver.javaClass
        var field: Field? = null
        while (type != null && field == null) {
            field = runCatching { type.getDeclaredField(name) }.getOrNull()
            type = type.superclass
        }
        return field?.apply { isAccessible = true }?.get(receiver)
    }

    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
}
