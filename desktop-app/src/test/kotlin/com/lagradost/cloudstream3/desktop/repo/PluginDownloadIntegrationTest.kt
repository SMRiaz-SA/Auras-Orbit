package com.lagradost.cloudstream3.desktop.repo

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class PluginDownloadIntegrationTest {
    @Test
    fun `corrupt primary download falls back to a verified mirror`() = runBlocking {
        val pluginBytes = testPluginArchive()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = CopyOnWriteArrayList<String>()
        server.createContext("/primary.jar") { exchange ->
            requests += exchange.requestURI.path
            val bytes = "corrupt package".toByteArray()
            exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/mirror.jar") { exchange ->
            requests += exchange.requestURI.path
            exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, pluginBytes.size.toLong())
            exchange.responseBody.use { it.write(pluginBytes) }
        }
        server.start()

        val directory = Files.createTempDirectory("plugin-download-mirror-test").toFile()
        val client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            val pluginFile = File(directory, "Fixture.jar")
            val plugin = SitePlugin(
                name = "Fixture",
                internalName = "Fixture",
                url = "$baseUrl/primary.jar",
                version = 1,
                fileHash = hash(pluginBytes),
            )
            val mirror = plugin.copy(url = "$baseUrl/mirror.jar")
            val candidateUrls = listOf(plugin.url) + PluginFileUtils.alternatePluginUrls(plugin, listOf("Mirror" to mirror))

            val downloaded = PluginFileUtils.downloadPlugin(
                plugin,
                pluginFile,
                candidateUrls,
                client,
            )

            assertEquals(pluginFile.canonicalFile, assertNotNull(downloaded).canonicalFile)
            assertContentEquals(pluginBytes, pluginFile.readBytes())
            assertEquals(listOf("/primary.jar", "/mirror.jar"), requests)
            assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
            server.stop(0)
            directory.deleteRecursively()
        }
    }

    @Test
    fun `alternate plugin urls require the same identity and verified hash`() {
        val plugin = SitePlugin(
            name = "Fixture",
            internalName = "Fixture",
            url = "https://primary.example/fixture.jar",
            version = 1,
            fileHash = "sha256-expected",
        )
        val matchingMirror = plugin.copy(url = "https://mirror.example/fixture.jar")
        val wrongIdentity = plugin.copy(internalName = "Other", url = "https://mirror.example/other.jar")
        val wrongHash = plugin.copy(url = "https://mirror.example/wrong-hash.jar", fileHash = "sha256-other")
        val insecureMirror = plugin.copy(url = "ftp://mirror.example/fixture.jar")

        assertEquals(
            listOf(matchingMirror.url),
            PluginFileUtils.alternatePluginUrls(
                plugin,
                listOf(
                    "Mirror" to matchingMirror,
                    "Other identity" to wrongIdentity,
                    "Wrong hash" to wrongHash,
                    "Insecure scheme" to insecureMirror,
                ),
            ),
        )
    }

    @Test
    fun `failed primary and mirror preserve installed plugin and remove temp files`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/primary.jar") { exchange ->
            exchange.sendResponseHeaders(HttpURLConnection.HTTP_UNAVAILABLE, -1)
            exchange.close()
        }
        server.createContext("/mirror.jar") { exchange ->
            exchange.sendResponseHeaders(HttpURLConnection.HTTP_UNAVAILABLE, -1)
            exchange.close()
        }
        server.start()

        val directory = Files.createTempDirectory("plugin-download-failure-test").toFile()
        val client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            val pluginFile = File(directory, "Fixture.jar").apply { writeText("installed version") }
            val plugin = SitePlugin(
                name = "Fixture",
                internalName = "Fixture",
                url = "$baseUrl/primary.jar",
                version = 2,
            )

            assertEquals(
                null,
                PluginFileUtils.downloadPlugin(
                    plugin,
                    pluginFile,
                    listOf(plugin.url, "$baseUrl/mirror.jar"),
                    client,
                ),
            )

            assertEquals("installed version", pluginFile.readText())
            assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
            server.stop(0)
            directory.deleteRecursively()
        }
    }

    private fun testPluginArchive(): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("""{"name":"Fixture","internalName":"Fixture","version":1}""".toByteArray())
            zip.closeEntry()
        }
        output.toByteArray()
    }

    private fun hash(bytes: ByteArray): String = "sha256-" + java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
