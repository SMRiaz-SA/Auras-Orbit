package audit
import com.lagradost.cloudstream3.subtitles.SubtitleResource
import okio.BufferedSource
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

object SubtitleResourceLifecycleRegression {
    private var assertions = 0
    private fun check(value: Boolean, label: String) {
        assertions++
        if (!value) throw AssertionError(label)
    }
    @JvmStatic fun main(args: Array<String>) {
        val work = Files.createTempDirectory("orbit-resource-test").toFile()
        try {
            val resource = SubtitleResource()
            val files = (0 until 3).map { index ->
                val file = File.createTempFile("subtitle-", ".srt", work)
                file.writeText("subtitle-$index")
                "subtitle-$index.srt" to file
            }
            var called = 0
            try {
                resource.registerExtractedFiles(files) { name, _ ->
                    called++
                    if (called == 2) throw IllegalStateException("hostile plugin name callback")
                    name
                }
                throw AssertionError("Hostile callback did not throw")
            } catch (_: IllegalStateException) { }
            check(resource.getSubtitles().isEmpty(), "atomic registration rollback")
            for ((_, file) in files) check(!file.exists(), "callback failure file cleanup")

            val successful = (0 until 2).map { index ->
                val file = File.createTempFile("subtitle-", ".srt", work)
                file.writeText("content$index")
                "ok$index.srt" to file
            }
            resource.registerExtractedFiles(successful) { name, _ -> "caption:$name" }
            check(resource.getSubtitles().size == 2, "all subtitles registered")
            check(resource.getSubtitles().map { it.name } == listOf("caption:ok0.srt", "caption:ok1.srt"), "names match")
            check(successful.all { it.second.exists() }, "registered files still present")
            successful.forEach { it.second.delete() }

            val small = object : BufferedSource {
                override fun inputStream(): InputStream = ByteArrayInputStream("hello".toByteArray())
            }
            val downloaded = resource.downloadFile(small)
            check(downloaded.readText() == "hello", "downloadFile retains bytes")
            downloaded.delete()
            val oversized = object : BufferedSource {
                override fun inputStream(): InputStream = object : InputStream() {
                    var left = 16 * 1024 * 1024 + 1
                    override fun read(): Int = if (left-- > 0) 1 else -1
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        val count = minOf(len, left)
                        if (count == 0) return -1
                        java.util.Arrays.fill(b, off, off + count, 1.toByte()); left -= count
                        return count
                    }
                }
            }
            try {
                resource.downloadFile(oversized)
                throw AssertionError("oversized download was accepted")
            } catch (_: IOException) { }
            check(true, "oversized download rejected")
            println("SUBTITLE_LIFECYCLE_PASS checks=$assertions")
        } finally {
            work.listFiles()?.forEach { it.delete() }
            work.delete()
        }
    }
}
