package com.lagradost.cloudstream3.desktop.init

import com.lagradost.common.platform.PlatformPaths
import java.io.File

internal fun privateCrashTrace(failure: Throwable): String =
    com.lagradost.common.logging.LogBuffer.sanitizeThrowable(failure).stackTraceToString()

fun initCrashHandler() {
    Thread.setDefaultUncaughtExceptionHandler { _, e ->
        try {
            val crashDir = PlatformPaths.appDataDir
            crashDir.mkdirs()
            val crashFile = File(crashDir, "crash.log")

            val stackTrace = privateCrashTrace(e)
            val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())

            crashFile.appendText("\n\n--- CRASH LOG: $time ---\n")
            crashFile.appendText(stackTrace)

            try {
                java.awt.Desktop.getDesktop().open(crashDir)
            } catch (t: Throwable) {
                // Ignore if opening folder fails
            }

            javax.swing.JOptionPane.showMessageDialog(
                null,
                "Auras Orbit encountered a fatal error and closed.\n\nA crash log has been saved to:\n${crashFile.absolutePath}\n\nPlease share this file with the Auras Orbit developers.",
                "Auras Orbit Crash Reporter",
                javax.swing.JOptionPane.ERROR_MESSAGE,
            )
        } catch (t: Throwable) {
            // Failsafe, don't crash the crash handler itself
            System.err.println(privateCrashTrace(t))
        }
        kotlin.system.exitProcess(1)
    }
}
