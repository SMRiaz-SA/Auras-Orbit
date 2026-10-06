package com.lagradost.common.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DatabaseMigrationTest {
    @Test
    fun legacyBookmarkSchemaPreservesRowsAndCreatesBackup() {
        val directory = Files.createTempDirectory("auras-migration").toFile()
        val file = directory.resolve("legacy.db")
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
            connection.createStatement().use {
                it.execute("CREATE TABLE Bookmarks(id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, url TEXT NOT NULL, apiName TEXT NOT NULL, posterUrl TEXT)")
                it.execute("INSERT INTO Bookmarks VALUES ('one', 'Saved title', 'fixture://one', 'Fixture', NULL)")
            }
        }
        val database = DatabaseFactory.open(file)
        val saved = database.cloudstreamDBQueries.selectAllBookmarks().executeAsOne()
        assertEquals("Saved title", saved.name)
        assertEquals(0L, saved.watchType)
        assertTrue(directory.listFiles().orEmpty().any { it.name.startsWith("legacy.db.before-v5-") })
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA user_version").use { result ->
                    result.next()
                    assertEquals(5, result.getInt(1))
                }
            }
        }
    }

    @Test
    fun futureDatabaseIsRejectedWithoutDowngrade() {
        val file = Files.createTempDirectory("auras-future-schema").resolve("future.db").toFile()
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
            connection.createStatement().use { it.execute("PRAGMA user_version = 99") }
        }
        assertFailsWith<IllegalStateException> { DatabaseFactory.open(file) }
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA user_version").use { result ->
                    result.next()
                    assertEquals(99, result.getInt(1))
                }
            }
        }
    }

    @Test
    fun versionsOneThroughFourRekeyWatchHistoryWithoutConflatingHashCollisions() {
        val directory = Files.createTempDirectory("auras-history-key-migration").toFile()
        val firstUrl = "https://fixture.invalid/Aa"
        val secondUrl = "https://fixture.invalid/BB"
        assertEquals(firstUrl.hashCode(), secondUrl.hashCode())

        for (version in 1..4) {
            val file = directory.resolve("v$version.db")
            createVersionedHistoryDatabase(file, version, firstUrl, secondUrl)

            val database = DatabaseFactory.open(file)
            val rows = database.cloudstreamDBQueries.selectAllWatchHistory().executeAsList()
            assertEquals(2, rows.size)
            assertEquals(setOf(firstUrl, secondUrl), rows.map { it.showUrl }.toSet())
            assertEquals(2, rows.map { it.parentId }.toSet().size)
            assertTrue(rows.all { it.parentId.startsWith("p42_") })
            assertNotNull(directory.listFiles().orEmpty().find { it.name.startsWith("v$version.db.before-v5-") })

            DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA user_version").use { result ->
                        result.next()
                        assertEquals(5, result.getInt(1))
                    }
                    statement.executeQuery("SELECT COUNT(*) FROM EpisodeWatchMarks").use { result ->
                        result.next()
                        assertEquals(if (version == 1) 2L else 0L, result.getLong(1))
                    }
                }
            }
        }
    }

    @Test
    fun failedHistoryRekeyRollsBackAndPreservesPreUpgradeBackup() {
        val directory = Files.createTempDirectory("auras-history-key-rollback").toFile()
        val file = directory.resolve("rollback.db")
        val showUrl = "https://fixture.invalid/rollback"
        val oldParentId = "p42_Fixture_${showUrl.hashCode()}"
        createVersionedHistoryDatabase(file, 4, showUrl)
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "CREATE TRIGGER fail_history_rekey BEFORE UPDATE OF parentId ON WatchHistory " +
                        "BEGIN SELECT RAISE(ABORT, 'injected rekey failure'); END",
                )
            }
        }

        assertFailsWith<IllegalStateException> { DatabaseFactory.open(file) }
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA user_version").use { result ->
                    result.next()
                    assertEquals(4, result.getInt(1))
                }
                statement.executeQuery("SELECT parentId FROM WatchHistory").use { result ->
                    assertTrue(result.next())
                    assertEquals(oldParentId, result.getString(1))
                }
            }
        }
        assertNotNull(directory.listFiles().orEmpty().find { it.name.startsWith("rollback.db.before-v5-") })
    }

    private fun createVersionedHistoryDatabase(
        file: java.io.File,
        version: Int,
        vararg showUrls: String,
    ) {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        DesktopDatabase.Schema.create(driver)
        driver.close()

        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
            connection.prepareStatement(
                "INSERT INTO WatchHistory(parentId, episodeId, showName, showUrl, apiName, season, episode, position, duration, updateTime) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            ).use { statement ->
                showUrls.forEachIndexed { index, showUrl ->
                    statement.setString(1, "p42_Fixture_${showUrl.hashCode()}")
                    statement.setString(2, "episode-$index")
                    statement.setString(3, "Fixture $index")
                    statement.setString(4, showUrl)
                    statement.setString(5, "Fixture")
                    statement.setLong(6, 1L)
                    statement.setLong(7, (index + 1).toLong())
                    statement.setLong(8, 9_500L)
                    statement.setLong(9, 10_000L)
                    statement.setLong(10, 100L + index)
                    statement.executeUpdate()
                }
            }
            connection.createStatement().use { it.execute("PRAGMA user_version = $version") }
        }
    }
}
