package com.lagradost.common.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.lagradost.common.platform.PlatformPaths
import java.io.File
import java.sql.DriverManager

object DatabaseFactory {
    val database: DesktopDatabase by lazy {
        open(File(PlatformPaths.dataDir, "cloudstream.db"))
    }

    /** Version 1 reconciles historical unversioned desktop schemas without hiding failures. */
    internal fun open(dbFile: File): DesktopDatabase {
        dbFile.parentFile?.mkdirs()
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        try {
            DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
                val version = connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA user_version").use {
                        it.next()
                        it.getInt(1)
                    }
                }
                check(version <= 1) { "This database belongs to a newer Auras Orbit version." }
                if (version == 0 && dbFile.length() > 0) {
                    val backup = File(dbFile.parentFile, dbFile.name + ".before-v1-" + System.currentTimeMillis() + ".bak")
                    val escaped = backup.absolutePath.replace("'", "''")
                    connection.createStatement().use { it.execute("VACUUM INTO '$escaped'") }
                }
            }
            // IF NOT EXISTS creates missing tables while retaining all existing rows.
            DesktopDatabase.Schema.create(driver)
            DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
                connection.autoCommit = false
                try {
                    val additions = mapOf(
                        "Bookmarks" to mapOf("watchType" to "INTEGER DEFAULT 0", "dateAdded" to "INTEGER DEFAULT 0"),
                        "WatchHistory" to mapOf(
                            "episodeThumbnailUrl" to "TEXT",
                            "screenshotUrl" to "TEXT",
                            "episodeName" to "TEXT",
                            "episodeDescription" to "TEXT",
                        ),
                    )
                    additions.forEach { (table, columns) ->
                        val existing = connection.createStatement().use { statement ->
                            statement.executeQuery("PRAGMA table_info($table)").use { result ->
                                buildSet { while (result.next()) add(result.getString("name")) }
                            }
                        }
                        columns.filterKeys { it !in existing }.forEach { (column, type) ->
                            connection.createStatement().use { it.execute("ALTER TABLE $table ADD COLUMN $column $type") }
                        }
                    }
                    connection.createStatement().use { it.execute("PRAGMA user_version = 1") }
                    connection.commit()
                } catch (failure: Exception) {
                    connection.rollback()
                    throw failure
                }
            }
            return DesktopDatabase(driver)
        } catch (failure: Exception) {
            driver.close()
            throw IllegalStateException("Database upgrade failed; original data and any pre-upgrade backup were preserved.", failure)
        }
    }
}
