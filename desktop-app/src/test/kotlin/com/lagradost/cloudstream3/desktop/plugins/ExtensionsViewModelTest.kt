package com.lagradost.cloudstream3.desktop.plugins

import com.lagradost.cloudstream3.desktop.repo.DesktopRepositoryManager
import com.lagradost.cloudstream3.desktop.repo.SitePlugin
import com.lagradost.cloudstream3.desktop.ui.screens.extensions.InstalledPluginArchiveIdentity
import com.lagradost.cloudstream3.desktop.ui.screens.extensions.ProviderPreferenceIdentity
import com.lagradost.cloudstream3.desktop.ui.screens.extensions.activeProviderKeysAfterPluginUninstall
import com.lagradost.cloudstream3.desktop.ui.screens.extensions.contract.ExtensionsUiEvent
import com.lagradost.cloudstream3.desktop.ui.screens.extensions.contract.ExtensionsUiState
import com.lagradost.cloudstream3.desktop.ui.screens.extensions.pluginArtifactsToRemoveByIdentity
import com.lagradost.cloudstream3.desktop.ui.screens.extensions.repositoryForPluginStorageFolder
import com.lagradost.cloudstream3.desktop.ui.screens.extensions.selectInstalledPluginArchive
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExtensionsViewModelTest {

    @Test
    fun uninstallRemovesExactProviderKeysWithoutMatchingNameSubstrings() {
        val directory = Files.createTempDirectory("provider-pref-identity-").toFile()
        try {
            val target = directory.resolve("TargetRepo/target.jar").apply { parentFile.mkdirs() }
            val other = directory.resolve("OtherRepo/other.jar").apply { parentFile.mkdirs() }
            val targetPath = target.absolutePath
            val providers = listOf(
                ProviderPreferenceIdentity("Star", targetPath),
                ProviderPreferenceIdentity("Starz", other.absolutePath),
            )

            val updated = activeProviderKeysAfterPluginUninstall(
                activeKeys = listOf("TargetRepo::Star", "OtherRepo::Starz", "Star", "Starz"),
                targetSourcePaths = setOf(targetPath),
                providers = providers,
            )

            assertEquals(listOf("OtherRepo::Starz", "Starz"), updated)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun uninstallKeepsAmbiguousLegacyBareKeyWhenAnotherProviderHasSameName() {
        val directory = Files.createTempDirectory("provider-pref-duplicate-name-").toFile()
        try {
            val target = directory.resolve("TargetRepo/target.jar").apply { parentFile.mkdirs() }
            val other = directory.resolve("OtherRepo/other.jar").apply { parentFile.mkdirs() }
            val updated = activeProviderKeysAfterPluginUninstall(
                activeKeys = listOf("TargetRepo::Star", "OtherRepo::Star", "Star"),
                targetSourcePaths = setOf(target.absolutePath),
                providers = listOf(
                    ProviderPreferenceIdentity("Star", target.absolutePath),
                    ProviderPreferenceIdentity("Star", other.absolutePath),
                ),
            )

            assertEquals(listOf("OtherRepo::Star", "Star"), updated)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun uninstallRemovesStaleQualifiedKeyForEveryTargetArchiveFolder() {
        val directory = Files.createTempDirectory("provider-pref-duplicate-archives-").toFile()
        try {
            val activeCopy = directory.resolve("TargetRepo/active.jar").apply { parentFile.mkdirs() }
            val staleCopy = directory.resolve("OtherRepo/stale.jar").apply { parentFile.mkdirs() }
            val updated = activeProviderKeysAfterPluginUninstall(
                activeKeys = listOf("TargetRepo::Star", "OtherRepo::Star", "OtherRepo::OtherPlugin"),
                targetSourcePaths = setOf(activeCopy.absolutePath, staleCopy.absolutePath),
                providers = listOf(ProviderPreferenceIdentity("Star", activeCopy.absolutePath)),
            )

            assertEquals(listOf("OtherRepo::OtherPlugin"), updated)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun uninstallPreservesQualifiedKeyOwnedBySameNameProviderInSameFolder() {
        val directory = Files.createTempDirectory("provider-pref-same-folder-owner-").toFile()
        try {
            val target = directory.resolve("SharedRepo/target.jar").apply { parentFile.mkdirs() }
            val other = directory.resolve("SharedRepo/other.jar")
            val updated = activeProviderKeysAfterPluginUninstall(
                activeKeys = listOf("SharedRepo::Star", "Star"),
                targetSourcePaths = setOf(target.absolutePath),
                providers = listOf(
                    ProviderPreferenceIdentity("Star", target.absolutePath),
                    ProviderPreferenceIdentity("Star", other.absolutePath),
                ),
            )

            assertEquals(listOf("SharedRepo::Star", "Star"), updated)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun installedRepositoryMatchesItsStableStorageFolderAfterManifestRename() {
        val repository = RepositoryData(name = "Updated Catalog Name", url = "https://example.com/repo.json")
            .apply { storageName = "Original Catalog Name" }

        assertEquals(
            repository,
            repositoryForPluginStorageFolder(listOf(repository), "Original_Catalog_Name"),
        )
    }

    @Test
    fun installedArchiveInLegacySharedFolderHasNoArbitraryRepositoryAttribution() {
        val repositories = listOf(
            RepositoryData(name = "First Legacy Repo", url = "https://first.example/repo.json")
                .apply { storageName = "Legacy Shared Folder" },
            RepositoryData(name = "Second Legacy Repo", url = "https://second.example/repo.json")
                .apply { storageName = "Legacy Shared Folder" },
        )

        assertNull(repositoryForPluginStorageFolder(repositories, "Legacy_Shared_Folder"))
    }

    @Test
    fun installedArchiveSelectionPrefersLoadedPathAndUsesStableFallback() {
        val directory = Files.createTempDirectory("installed-plugin-selection-").toFile()
        try {
            val first = directory.resolve("a.jar").apply { writeText("a") }
            val second = directory.resolve("z.jar").apply { writeText("z") }
            val newest = directory.resolve("m.jar").apply { writeText("m") }
            first.setLastModified(1_000L)
            second.setLastModified(1_000L)
            newest.setLastModified(2_000L)

            assertEquals(second.canonicalFile, selectInstalledPluginArchive(listOf(first, second), second)?.canonicalFile)
            assertEquals(first.canonicalFile, selectInstalledPluginArchive(listOf(second, first), null)?.canonicalFile)
            assertEquals(newest.canonicalFile, selectInstalledPluginArchive(listOf(first, newest, second), null)?.canonicalFile)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun uninstallArtifactSelectionRemovesEveryIdentityCopyAndProtectsSharedArtifacts() {
        val directory = Files.createTempDirectory("plugin-uninstall-identity-").toFile()
        try {
            val anotherRepo = directory.resolve("another-repo").apply { mkdirs() }
            val targetCs3 = directory.resolve("shared.cs3")
            val otherIdentityJar = directory.resolve("shared.jar")
            val targetCopy = anotherRepo.resolve("shared.jar")
            val toDelete = pluginArtifactsToRemoveByIdentity(
                archives = listOf(
                    InstalledPluginArchiveIdentity(targetCs3, "target.plugin"),
                    InstalledPluginArchiveIdentity(targetCopy, "target.plugin"),
                    InstalledPluginArchiveIdentity(otherIdentityJar, "other.plugin"),
                ),
                internalNames = setOf("target.plugin"),
            ).getValue("target.plugin").map { it.canonicalFile }.toSet()

            assertTrue(targetCs3.canonicalFile in toDelete)
            assertTrue(targetCopy.canonicalFile in toDelete)
            assertFalse(otherIdentityJar.canonicalFile in toDelete)
            assertFalse(directory.resolve("shared-jvm.jar").canonicalFile in toDelete)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun testInitialState_Defaults() {
        val state = ExtensionsUiState()
        assertFalse(state.isFetching)
        assertFalse(state.isUninstalling)
        assertNull(state.inspectedRepoName)
        assertTrue(state.installedPlugins.isEmpty())
        assertTrue(state.plugins.isEmpty())
        assertTrue(state.updatesHistory.isEmpty())
    }

    @Test
    fun testInspectRepository_StateReduction() {
        var state = ExtensionsUiState()

        // Simulate OnInspectRepository("MainRepo")
        val inspectEvent = ExtensionsUiEvent.OnInspectRepository("MainRepo")
        state = state.copy(inspectedRepoName = inspectEvent.repoName)
        assertEquals("MainRepo", state.inspectedRepoName)

        // Simulate clearing inspection
        state = state.copy(inspectedRepoName = null)
        assertNull(state.inspectedRepoName)
    }

    @Test
    fun testFilterMatchingLogic() {
        val p1 = SitePlugin(
            name = "AnimeStreamer",
            internalName = "com.example.animestreamer",
            url = "https://example.com/p1.jar",
            version = 2,
            status = 1,
            authors = listOf("Dev1"),
            tvTypes = listOf("Anime"),
            language = "en",
        )
        val p2 = SitePlugin(
            name = "MovieFlix",
            internalName = "com.example.movieflix",
            url = "https://example.com/p2.jar",
            version = 1,
            status = 1,
            authors = listOf("Dev2"),
            tvTypes = listOf("Movie", "TvSeries"),
            language = "es",
        )

        val plugins = listOf("RepoA" to p1, "RepoB" to p2)

        // Search query filter
        val searchAnime = plugins.filter {
            it.second.name.contains("Anime", ignoreCase = true) || it.second.internalName.contains("Anime", ignoreCase = true)
        }
        assertEquals(1, searchAnime.size)
        assertEquals("AnimeStreamer", searchAnime[0].second.name)

        // Category filter
        val moviePlugins = plugins.filter {
            it.second.tvTypes?.contains("Movie") == true
        }
        assertEquals(1, moviePlugins.size)
        assertEquals("MovieFlix", moviePlugins[0].second.name)

        // Language filter
        val esPlugins = plugins.filter { it.second.language == "es" }
        assertEquals(1, esPlugins.size)
        assertEquals("MovieFlix", esPlugins[0].second.name)

        // Repository filter
        val repoAPlugins = plugins.filter { it.first == "RepoA" }
        assertEquals(1, repoAPlugins.size)
        assertEquals("AnimeStreamer", repoAPlugins[0].second.name)
    }

    @Test
    fun testInstalledPluginIdentityIsGlobalAcrossRepositories() {
        val installedIdentities = setOf("com.example.animestreamer")
        val candidateFromAnotherRepository = "com.example.animestreamer"
        val uninstalledCandidate = "com.example.uninstalled"

        assertTrue(installedIdentities.contains(candidateFromAnotherRepository))
        assertFalse(installedIdentities.contains(uninstalledCandidate))
    }

    @Test
    fun testSyncReport_SummaryFormatting() {
        val report = DesktopRepositoryManager.SyncReport(
            reposRefreshed = 3,
            catalogPlugins = 25,
            pluginsUpdated = 2,
            iconsCached = 10,
            newPluginsLoaded = 1,
        )
        assertEquals(3, report.reposRefreshed)
        assertEquals(25, report.catalogPlugins)
        assertEquals(2, report.pluginsUpdated)
        assertEquals(10, report.iconsCached)
        assertEquals(1, report.newPluginsLoaded)
        assertTrue(report.summary.contains("3 repos"))
        assertTrue(report.summary.contains("25 plugins listed"))
    }
}
