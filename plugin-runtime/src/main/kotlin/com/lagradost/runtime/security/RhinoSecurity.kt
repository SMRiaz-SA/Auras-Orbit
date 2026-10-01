package com.lagradost.runtime.security

/**
 * Compatibility facade retained for older callers. Plugin scripts use Rhino's normal Java interop.
 */
object RhinoSecurity {
    fun init() = Unit
}
