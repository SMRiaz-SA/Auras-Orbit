package com.lagradost.cloudstream3.utils

import com.lagradost.cloudstream3.serializeCs3ProviderData

/** Orbit's source-shaped AppUtils object used by pinned CS3 provider helpers. */
object AppUtils {
    /** Preserves CS3's raw String pass-through and otherwise uses the shared serializer path. */
    fun Any.toJson(): String = if (this is String) this else serializeCs3ProviderData(this)
}
