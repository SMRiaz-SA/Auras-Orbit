package com.lagradost.common.db

import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        assertTrue(directory.listFiles().orEmpty().any { it.name.startsWith("legacy.db.before-v1-") })
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA user_version").use { result ->
                    result.next()
                    assertEquals(1, result.getInt(1))
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
}
