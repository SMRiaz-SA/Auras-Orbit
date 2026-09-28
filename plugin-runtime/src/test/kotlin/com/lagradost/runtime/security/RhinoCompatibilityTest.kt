package com.lagradost.runtime.security

import org.junit.jupiter.api.Test
import org.mozilla.javascript.Context
import kotlin.test.assertNotNull

class RhinoCompatibilityTest {
    @Test
    fun `plugin scripts retain standard Rhino Java interop`() {
        RhinoSecurity.init()
        val context = Context.enter()
        try {
            val scope = context.initStandardObjects()
            val javaVersion = context.evaluateString(
                scope,
                "java.lang.System.getProperty('java.version')",
                "plugin-interop-test",
                1,
                null,
            )
            assertNotNull(javaVersion)
        } finally {
            Context.exit()
        }
    }
}
