package com.lagradost.cloudstream3.desktop.player

import com.lagradost.cloudstream3.desktop.ui.screens.player.PlayerState

internal data class MpvTrackIdentity(val id: Int, val name: String)

internal data class MpvPlaybackStateSnapshot(
    val positionMs: Long,
    val volume: Float,
    val playbackSpeed: Float,
    val isMuted: Boolean,
    val isPaused: Boolean,
    val subtitleDelayMs: Long,
    val subtitlesVisible: Boolean,
    val selectedAudioTrack: MpvTrackIdentity?,
    val selectedSubtitleTrack: MpvTrackIdentity?,
    val selectedVideoTrack: MpvTrackIdentity?,
    val hasAudioTracks: Boolean,
    val hasSubtitleTracks: Boolean,
    val hasVideoTracks: Boolean,
    val isAudioMode: Boolean,
)

internal fun captureMpvPlaybackState(state: PlayerState): MpvPlaybackStateSnapshot = MpvPlaybackStateSnapshot(
    positionMs = state.positionMs.value,
    volume = state.volume.value,
    playbackSpeed = state.playbackSpeed.value,
    isMuted = state.isMuted.value,
    isPaused = state.isPaused.value,
    subtitleDelayMs = state.subtitleDelayMs.value,
    subtitlesVisible = state.isSubtitleVisible.value,
    selectedAudioTrack = state._audioTracks.value.selectedTrackIdentity(),
    selectedSubtitleTrack = state._subtitleTracks.value.selectedTrackIdentity(),
    selectedVideoTrack = state._videoTracks.value.selectedTrackIdentity(),
    hasAudioTracks = state._audioTracks.value.isNotEmpty(),
    hasSubtitleTracks = state._subtitleTracks.value.isNotEmpty(),
    hasVideoTracks = state._videoTracks.value.isNotEmpty(),
    isAudioMode = state._isAudioMode.value,
)

private fun List<PlayerState.VideoTrack>.selectedTrackIdentity(): MpvTrackIdentity? =
    firstOrNull { it.isSelected }?.let { MpvTrackIdentity(it.id, it.name) }

/** Prefer the original MPV id, but use its stable displayed identity if ids shift on reload. */
internal fun resolveRestoredMpvTrackId(
    identity: MpvTrackIdentity?,
    availableTracks: List<PlayerState.VideoTrack>,
): Int? {
    identity ?: return null
    return availableTracks.firstOrNull { it.id == identity.id && it.name == identity.name }?.id
        ?: availableTracks.singleOrNull { it.name == identity.name }?.id
}
