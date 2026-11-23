package com.lagradost.cloudstream3.desktop.repo

import com.lagradost.cloudstream3.desktop.data.plugins.PluginRepositoryImpl
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RepositoryRefreshTest {
    @Test
    fun legacyRepositoryFileWithDuplicateNamesIsNormalizedWithoutChangingStorageFolders() {
        val directory = Files.createTempDirectory("legacy-repositories-").toFile()
        val reposFile = directory.resolve("repos.json")
        try {
            reposFile.writeText(
                """[
                  {"name":"Legacy Shared","url":"https://first.example/repo.json","storageName":"Installed First"},
                  {"name":"Legacy Shared","url":"https://second.example/repo.json","storageName":"Installed Second"}
                ]
                """.trimIndent(),
            )
            val stored = PluginNetworkClient.mapper.readValue(
                reposFile,
                object : com.fasterxml.jackson.core.type.TypeReference<List<RepositoryData>>() {},
            )

            val normalized = normalizeRepositoryRows(stored)

            assertEquals(listOf("Legacy Shared", "Legacy Shared (2)"), normalized.map { it.name })
            assertEquals(listOf("Installed First", "Installed Second"), normalized.map { it.storageName })
            reposFile.writeText(PluginNetworkClient.mapper.writeValueAsString(normalized))
            val reopened = PluginNetworkClient.mapper.readValue(
                reposFile,
                object : com.fasterxml.jackson.core.type.TypeReference<List<RepositoryData>>() {},
            )
            assertEquals(normalized.map { it.name }, reopened.map { it.name })
            assertEquals(normalized.map { it.storageName }, reopened.map { it.storageName })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun legacyDuplicateStorageFoldersRemainOnDiskButAreQuarantinedFromFileOperations() {
        val directory = Files.createTempDirectory("legacy-shared-repository-folder-").toFile()
        val reposFile = directory.resolve("repos.json")
        try {
            reposFile.writeText(
                """[
                  {"name":"Legacy Shared","url":"https://first.example/repo.json","storageName":"Shared Folder"},
                  {"name":"Legacy Shared","url":"https://second.example/repo.json","storageName":"Shared Folder"}
                ]
                """.trimIndent(),
            )
            val stored = PluginNetworkClient.mapper.readValue(
                reposFile,
                object : com.fasterxml.jackson.core.type.TypeReference<List<RepositoryData>>() {},
            )
            val normalized = normalizeRepositoryRows(stored)

            assertEquals(listOf("Legacy Shared", "Legacy Shared (2)"), normalized.map { it.name })
            assertEquals(listOf("Shared Folder", "Shared Folder"), normalized.map { it.storageName })
            assertTrue(PluginFileUtils.hasRepositoryFolderCollision(normalized, normalized[0].name))
            assertTrue(PluginFileUtils.hasRepositoryFolderCollision(normalized, normalized[1].name))
            assertTrue(PluginFileUtils.hasRepositoryFolderCollision(normalized, "Shared_Folder"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun pluginRepositoryAddSaveBoundaryDisambiguatesDuplicateNamesAndFolders() = runBlocking {
        val suffix = System.nanoTime().toString()
        val baseName = "Plugin Added Repository $suffix"
        val firstUrl = "https://first.example/$suffix/repo.json"
        val secondUrl = "https://second.example/$suffix/repo.json"

        try {
            // RepositoryManager.addRepository() writes through this same centralized save boundary.
            DesktopRepositoryManager.saveRepository(RepositoryData(baseName, firstUrl))
            DesktopRepositoryManager.saveRepository(RepositoryData(baseName, secondUrl))

            val saved = DesktopRepositoryManager.getSavedRepositories().filter { it.url in setOf(firstUrl, secondUrl) }
            val first = saved.single { it.url == firstUrl }
            val second = saved.single { it.url == secondUrl }
            assertEquals(baseName, first.name)
            assertEquals("$baseName (2)", second.name)
            assertNotEquals(first.storageName, second.storageName)
            assertNotEquals(
                PluginFileUtils.repositoryDirectory(first.name),
                PluginFileUtils.repositoryDirectory(second.name),
            )
        } finally {
            DesktopRepositoryManager.removeRepository(firstUrl)
            DesktopRepositoryManager.removeRepository(secondUrl)
        }
    }

    @Test
    fun repositoryAddRouteDisambiguatesManifestNamesInsteadOfRejectingSecondRepository() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base = "http://127.0.0.1:${server.address.port}"
        val suffix = System.nanoTime().toString()
        val name = "Added Through Repository Route $suffix"
        val firstUrl = "$base/repo-a-$suffix.json"
        val secondUrl = "$base/repo-b-$suffix.json"
        listOf("repo-a-$suffix.json", "repo-b-$suffix.json").forEach { path ->
            server.createContext("/$path") { exchange ->
                val bytes = """{"name":"$name","pluginLists":[]}""".toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        server.start()
        try {
            val repository = PluginRepositoryImpl()
            assertEquals(1, repository.addRepository(firstUrl)?.size)
            assertEquals(1, repository.addRepository(secondUrl)?.size)

            val saved = DesktopRepositoryManager.getSavedRepositories().filter { it.url in setOf(firstUrl, secondUrl) }
            val first = saved.single { it.url == firstUrl }
            val second = saved.single { it.url == secondUrl }
            assertEquals(name, first.name)
            assertEquals("$name (2)", second.name)
            assertNotEquals(first.storageName, second.storageName)
            assertNotEquals(
                PluginFileUtils.repositoryDirectory(first.name),
                PluginFileUtils.repositoryDirectory(second.name),
            )
        } finally {
            DesktopRepositoryManager.removeRepository(firstUrl)
            DesktopRepositoryManager.removeRepository(secondUrl)
            server.stop(0)
        }
    }

    @Test
    fun metadataRenameToAnotherSavedRepositoryNameKeepsFolderResolutionUnique() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base = "http://127.0.0.1:${server.address.port}"
        val suffix = System.nanoTime().toString()
        val firstName = "Refresh Source A $suffix"
        val secondName = "Refresh Source B $suffix"
        val firstUrl = "$base/repo-a.json"
        val secondUrl = "$base/repo-b.json"
        server.createContext("/repo-a.json") { exchange ->
            val bytes = """{"name":"$secondName","pluginLists":[]}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/repo-b.json") { exchange ->
            val bytes = """{"name":"$secondName","pluginLists":[]}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            DesktopRepositoryManager.saveRepository(RepositoryData(firstName, firstUrl))
            DesktopRepositoryManager.saveRepository(RepositoryData(secondName, secondUrl))
            val firstFolder = PluginFileUtils.repositoryDirectory(firstName)
            val secondFolder = PluginFileUtils.repositoryDirectory(secondName)

            DesktopRepositoryManager.refreshAllRepositoryMetadata()

            val saved = DesktopRepositoryManager.getSavedRepositories()
            val first = saved.single { it.url == firstUrl }
            val second = saved.single { it.url == secondUrl }
            assertEquals("$secondName (2)", first.name)
            assertEquals(secondName, second.name)
            assertEquals(firstFolder, PluginFileUtils.repositoryDirectory(first.name))
            assertEquals(secondFolder, PluginFileUtils.repositoryDirectory(second.name))
            assertNotEquals(PluginFileUtils.repositoryDirectory(first.name), PluginFileUtils.repositoryDirectory(second.name))
        } finally {
            DesktopRepositoryManager.removeRepository(firstUrl)
            DesktopRepositoryManager.removeRepository(secondUrl)
            server.stop(0)
        }
    }

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
