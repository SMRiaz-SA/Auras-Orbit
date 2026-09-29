package com.lagradost.cloudstream3.syncproviders.providers

import com.lagradost.cloudstream3.syncproviders.SyncAPI

/**
 * Minimal desktop compatibility type for Android-built extensions that link against
 * AccountManager.aniListApi while constructing their provider objects.
 *
 * Desktop account synchronization is not implemented yet. The desktop SyncRepo reports no
 * authenticated user, so extensions can keep account-only views unavailable without preventing
 * their non-account features from loading.
 */
class AniListApi : SyncAPI()
