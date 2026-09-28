package com.lagradost.cloudstream3.desktop.init

import kotlin.test.Test
import kotlin.test.assertEquals

class RetiredTrackerDataCleanupTest {
    @Test
    fun removesRetiredTrackerDataWithoutTouchingSubtitleAccountsOrOtherSettings() {
        val keys = listOf(
            "auth_tokens_mal_default",
            "tracker_credentials_v1_auth_tokens_anilist_profile_5",
            "tracker_client_id_simkl",
            "3/tracker_auto_sync_v1_mal",
            "0/tracker_sync_health_v1_anilist",
            "auth_tokens_stremio_external_addons_profile_3",
            "auth_tokens_subdl_profile_3",
            "tracker_client_id_other",
            "0/tracker_auto_sync_v1_anime",
            "theme",
        )

        assertEquals(
            keys.take(5).toSet(),
            keys.filter(RetiredTrackerDataCleanup::isRetiredTrackerKey).toSet(),
        )
    }
}
