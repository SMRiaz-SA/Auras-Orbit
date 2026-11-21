package com.lagradost.cloudstream3

/** Pinned CS3 enum shape required by SearchResponse and MovieSearchResponse. */
enum class SearchQuality(value: Int?) {
    Cam(1),
    CamRip(2),
    HdCam(3),
    Telesync(4),
    WorkPrint(5),
    Telecine(6),
    HQ(7),
    HD(8),
    HDR(9),
    BlueRay(10),
    DVD(11),
    SD(12),
    FourK(13),
    UHD(14),
    SDR(15),
    WebRip(16),
}
