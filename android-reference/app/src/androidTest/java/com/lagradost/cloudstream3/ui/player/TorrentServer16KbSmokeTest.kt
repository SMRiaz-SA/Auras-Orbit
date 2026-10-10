package com.lagradost.cloudstream3.ui.player

import android.content.Context
import android.system.Os
import android.system.OsConstants
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import go.Seq
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import torrServer.TorrServer
import java.io.File

@RunWith(AndroidJUnit4::class)
class TorrentServer16KbSmokeTest {
    @Test
    fun startsNativeTorrentServerOn16KbPageSize() {
        val pageSize = Os.sysconf(OsConstants._SC_PAGESIZE)
        assumeTrue("This smoke test requires a 16 KB Android image", pageSize == 16_384L)

        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseDirectory = File(context.cacheDir, "torrentserver-16kb-smoke")
        assertTrue(
            "Could not create the isolated torrent database directory",
            databaseDirectory.mkdirs() || databaseDirectory.isDirectory
        )

        Seq.load()
        val port = TorrServer.startTorrentServer(databaseDirectory.absolutePath, 0)
        assertTrue("The 16 KB native torrent server did not start", port > 0)
    }
}
