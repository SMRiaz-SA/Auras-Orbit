package com.lagradost.runtime.security

/** Compatibility facade retained for older plugin-loader callers; plugin APIs are not allowlisted. */
object PluginSecurityPolicy {
    fun isPrivateHostApi(name: String): Boolean =
        name.startsWith("com.lagradost.cloudstream3.syncproviders.")

    @Suppress("UNUSED_PARAMETER")
    fun isClassAllowed(className: String, hasSocketPermission: Boolean = false, isTrusted: Boolean = false): Boolean = true

    @Suppress("UNUSED_PARAMETER")
    fun isAsmOwnerAllowed(internalName: String, isTrusted: Boolean = false): Boolean = true

    fun isSystemOrHostPackage(className: String): Boolean =
        className.startsWith("java.") ||
            className.startsWith("javax.") ||
            className.startsWith("sun.") ||
            className.startsWith("com.sun.") ||
            className.startsWith("jdk.") ||
            className.startsWith("com.oracle.") ||
            className.startsWith("com.lagradost.") ||
            className.startsWith("app.cash.sqldelight.") ||
            className.startsWith("org.bytedeco.")
}
