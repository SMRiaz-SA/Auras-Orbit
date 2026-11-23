package com.lagradost.cloudstream3.desktop.player

import com.lagradost.cloudstream3.desktop.ui.screens.player.PlayerState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MpvPlaybackStateSnapshotTest {
    @Test
    fun peerRecreationSnapshotPreservesPlaybackControlsAndSelectedTracks() {
        val state = PlayerState().apply {
            _positionMs.value = 146_000L
            _volume.value = 37f
            _playbackSpeed.value = 1.5f
            _isMuted.value = true
            _isPaused.value = true
            _isAudioMode.value = false
            _audioTracks.value = listOf(
                PlayerState.VideoTrack(1, "Japanese", false),
                PlayerState.VideoTrack(2, "English", true),
            )
            _subtitleTracks.value = listOf(
                PlayerState.VideoTrack(4, "English", true),
                PlayerState.VideoTrack(5, "French", false),
            )
            _videoTracks.value = listOf(PlayerState.VideoTrack(7, "1080p", true))
        }
        state.adjustSubtitleDelay(-1_250L)
        state.toggleSubtitleVisibility()

        val snapshot = captureMpvPlaybackState(state)

        assertEquals(146_000L, snapshot.positionMs)
        assertEquals(37f, snapshot.volume)
        assertEquals(1.5f, snapshot.playbackSpeed)
        assertTrue(snapshot.isMuted)
        assertTrue(snapshot.isPaused)
        assertEquals(-1_250L, snapshot.subtitleDelayMs)
        assertEquals(false, snapshot.subtitlesVisible)
        assertEquals(MpvTrackIdentity(2, "English"), snapshot.selectedAudioTrack)
        assertEquals(MpvTrackIdentity(4, "English"), snapshot.selectedSubtitleTrack)
        assertEquals(MpvTrackIdentity(7, "1080p"), snapshot.selectedVideoTrack)

        assertEquals(
            18,
            resolveRestoredMpvTrackId(
                snapshot.selectedAudioTrack,
                listOf(PlayerState.VideoTrack(18, "English", false)),
            ),
            "same-source track identity should survive MPV assigning a new numeric id",
        )
    }
}
