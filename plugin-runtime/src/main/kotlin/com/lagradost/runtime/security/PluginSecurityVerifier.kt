package com.lagradost.runtime.security

import java.io.File

/** Validates plugin archive structure without restricting its runtime APIs. */
object PluginSecurityVerifier {
    @Throws(SecurityException::class)
    @Suppress("UNUSED_PARAMETER")
    fun verifyJar(jarFile: File, pluginInternalName: String, isTrusted: Boolean = false) {
        PluginArchiveLimits.verify(jarFile)
    }
}

class RequiresPermissionException(val permissionName: String, message: String) : SecurityException(message)
