package com.lagradost.common.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.WatchHistoryKey
import java.io.File
import java.sql.DriverManager

object DatabaseFactory {
    val database: DesktopDatabase by lazy {
        open(File(PlatformPaths.dataDir, "cloudstream.db"))
    }

    /** Version 5 replaces collision-prone watch-history hashes with stable SHA-256 keys. */
    internal fun open(dbFile: File): DesktopDatabase {
        dbFile.parentFile?.mkdirs()
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        try {
            var version = 0
            DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
                version = connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA user_version").use {
                        it.next()
                        it.getInt(1)
                    }
                }
                check(version <= 5) { "This database belongs to a newer Auras Orbit version." }
                if (version < 5 && dbFile.length() > 0) {
                    val backup = File(dbFile.parentFile, dbFile.name + ".before-v5-" + System.currentTimeMillis() + ".bak")
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
                        "CustomLists" to mapOf("showOnHome" to "INTEGER NOT NULL DEFAULT 0"),
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
                    if (version < 2) {
                        connection.createStatement().use { statement ->
                            statement.executeUpdate(
                                """
                                INSERT OR IGNORE INTO EpisodeWatchMarks(
                                    profileId, providerName, showUrl, episodeKey, episodeId,
                                    showName, seasonNumber, episodeNumber, watchedAt
                                )
                                SELECT
                                    CASE
                                        WHEN parentId GLOB 'p[0-9]*_*' THEN CAST(substr(parentId, 2, instr(parentId, '_') - 2) AS INTEGER)
                                        ELSE 0
                                    END,
                                    apiName,
                                    showUrl,
                                    's' || COALESCE(season, 1) || ':e' || episode,
                                    episodeId,
                                    showName,
                                    COALESCE(season, 1),
                                    episode,
                                    updateTime
                                FROM WatchHistory
                                WHERE episode IS NOT NULL
                                  AND duration > 0
                                  AND position >= duration * 0.9
                                  AND episodeId != ''
                                """.trimIndent(),
                            )
                        }
                    }
                    if (version < 5) {
                        val rekeys = mutableListOf<HistoryRekey>()
                        connection.createStatement().use { statement ->
                            statement.executeQuery(
                                "SELECT parentId, episodeId, apiName, showUrl, season, episode FROM WatchHistory",
                            ).use { result ->
                                while (result.next()) {
                                    val season = result.getLong("season").takeUnless { result.wasNull() }
                                    val episode = result.getLong("episode").takeUnless { result.wasNull() }
                                    val oldParentId = result.getString("parentId")
                                    val episodeId = result.getString("episodeId")
                                    val newParentId = WatchHistoryKey.migrateLegacyId(
                                        parentId = oldParentId,
                                        apiName = result.getString("apiName"),
                                        showUrl = result.getString("showUrl"),
                                        season = season,
                                        episode = episode,
                                        episodeId = episodeId,
                                    )
                                    if (newParentId != null && newParentId != oldParentId) {
                                        rekeys += HistoryRekey(oldParentId, episodeId, newParentId)
                                    }
                                }
                            }
                        }
                        rekeys.forEach { rekey ->
                            connection.prepareStatement(
                                "UPDATE WatchHistory SET parentId = ? WHERE parentId = ? AND episodeId = ?",
                            ).use { statement ->
                                statement.setString(1, rekey.newParentId)
                                statement.setString(2, rekey.oldParentId)
                                statement.setString(3, rekey.episodeId)
                                check(statement.executeUpdate() == 1) { "A watch-history row changed during migration." }
                            }
                        }
                    }
                    connection.prepareStatement("DELETE FROM EpisodeReleaseCache WHERE fetchedAt < ?").use { statement ->
                        statement.setLong(1, System.currentTimeMillis() - 180L * 24L * 60L * 60L * 1000L)
                        statement.executeUpdate()
                    }
                    connection.createStatement().use { it.execute("PRAGMA user_version = 5") }
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

private data class HistoryRekey(
    val oldParentId: String,
    val episodeId: String,
    val newParentId: String,
)
