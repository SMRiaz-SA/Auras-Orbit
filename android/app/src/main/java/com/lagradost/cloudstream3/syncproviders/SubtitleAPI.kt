package com.lagradost.cloudstream3.syncproviders

import androidx.annotation.WorkerThread
import com.lagradost.cloudstream3.subtitles.AbstractSubtitleEntities.SubtitleEntity
import com.lagradost.cloudstream3.subtitles.AbstractSubtitleEntities.SubtitleSearch
import com.lagradost.cloudstream3.subtitles.SubtitleResource

/** Stateless subtitle API contract from pinned CloudStream. */
abstract class SubtitleAPI : AuthAPI() {
    @WorkerThread
    @Throws
    open suspend fun search(auth: AuthData?, query: SubtitleSearch): List<SubtitleEntity>? =
        throw NotImplementedError()

    @WorkerThread
    @Throws
    open suspend fun load(auth: AuthData?, subtitle: SubtitleEntity): String? =
        throw NotImplementedError()

    @WorkerThread
    @Throws
    open suspend fun SubtitleResource.getResources(auth: AuthData?, subtitle: SubtitleEntity) {
        this.addUrl(load(auth, subtitle), name = subtitle.name, headers = subtitle.headers)
    }

    @WorkerThread
    @Throws
    suspend fun resource(auth: AuthData?, subtitle: SubtitleEntity): SubtitleResource =
        SubtitleResource().apply { this.getResources(auth, subtitle) }
}
