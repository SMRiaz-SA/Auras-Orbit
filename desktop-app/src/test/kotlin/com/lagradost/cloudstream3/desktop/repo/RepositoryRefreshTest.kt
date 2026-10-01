package com.lagradost.cloudstream3.desktop.repo

import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RepositoryRefreshTest {
    @Test
    fun renamePreservesStorageAndFailedRefreshPreservesCatalog() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base = "http://127.0.0.1:${server.address.port}"
        var status = 200
        var catalog = """[{"name":"Fixture","internalName":"Fixture","url":"fixture.jar","version":1}]"""
        server.createContext("/repo.json") { exchange ->
            val bytes = """{"name":"Renamed Fixture","pluginLists":["$base/plugins.json"]}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/plugins.json") { exchange ->
            val bytes = catalog.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            DesktopRepositoryManager.saveRepository(RepositoryData("Original Fixture", "$base/repo.json"))
            val original = PluginFileUtils.repositoryDirectory("Original Fixture")
            DesktopRepositoryManager.refreshAllRepositoryMetadata()
            assertEquals(original, PluginFileUtils.repositoryDirectory("Renamed Fixture"))
            val persisted = DesktopRepositoryManager.getSavedRepositories().single { it.url == "$base/repo.json" }
            val restored = PluginNetworkClient.mapper.readValue(PluginNetworkClient.mapper.writeValueAsString(persisted), RepositoryData::class.java)
            assertEquals("Original Fixture", restored.storageName)
            DesktopRepositoryManager.rebuildRemotePluginCatalog()
            assertEquals(1, DesktopRepositoryManager.getCachedPlugins("$base/plugins.json").size)
            status = 503
            DesktopRepositoryManager.rebuildRemotePluginCatalog()
            assertEquals(1, DesktopRepositoryManager.getCachedPlugins("$base/plugins.json").size)
            status = 200
            catalog = "[]"
            DesktopRepositoryManager.rebuildRemotePluginCatalog()
            assertTrue(DesktopRepositoryManager.getCachedPlugins("$base/plugins.json").isEmpty())
        } finally {
            DesktopRepositoryManager.removeRepository("$base/repo.json")
            server.stop(0)
        }
    }
}
