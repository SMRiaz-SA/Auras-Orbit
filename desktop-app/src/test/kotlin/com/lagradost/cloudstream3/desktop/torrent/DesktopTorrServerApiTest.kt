package com.lagradost.cloudstream3.desktop.torrent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.common.storage.DesktopDataStore
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopTorrServerApiTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun addGetAndDropUseSupportedHashScopedActionsAndAcceptEmptyDropResponse() = runBlocking {
        val requests = LinkedBlockingQueue<JsonNode>()
        val fixture = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fixture.createContext("/torrents") { exchange ->
            val request = mapper.readTree(exchange.requestBody)
            requests.put(request)
            val response = when (request.path("action").asText()) {
                "add" -> """{"hash":"0123456789abcdef0123456789abcdef01234567"}"""
                "get" -> """{"hash":"0123456789abcdef0123456789abcdef01234567","file_stats":[{"id":1,"path":"fixture.mp4","length":4096}]}"""
                else -> ""
            }.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            if (response.isEmpty()) {
                exchange.sendResponseHeaders(200, -1)
            } else {
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            exchange.close()
        }
        fixture.start()

        val previousPort = DesktopDataStore.getKey<Int>(DesktopDataStore.PREF_P2P_PORT)
        DesktopDataStore.setKey(DesktopDataStore.PREF_P2P_PORT, fixture.address.port)
        try {
            val api = DesktopTorrServerApi(DesktopTorrServerBinary())
            val magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"
            val hash = api.addTorrent(magnet, title = "Fixture torrent")
            val stats = api.getTorrentStats(hash!!)

            assertEquals("0123456789abcdef0123456789abcdef01234567", hash)
            assertNotNull(stats)
            assertEquals("fixture.mp4", stats.fileStats.single().path)
            assertTrue(api.dropTorrent(hash))

            val add = requests.poll()
            assertEquals("add", add.path("action").asText())
            assertEquals(magnet, add.path("link").asText())
            assertEquals("Fixture torrent", add.path("title").asText())
            assertTrue(add.has("save_to_db"))
            assertFalse(add.path("save_to_db").asBoolean())

            val get = requests.poll()
            assertEquals("get", get.path("action").asText())
            assertEquals(hash, get.path("hash").asText())

            val drop = requests.poll()
            assertEquals("drop", drop.path("action").asText())
            assertEquals(hash, drop.path("hash").asText())
            assertTrue(listOf(add, get, drop).all { it.path("action").asText() in setOf("add", "get", "drop") })
            assertTrue(requests.isEmpty())
        } finally {
            fixture.stop(0)
            if (previousPort == null) {
                DesktopDataStore.removeKey(DesktopDataStore.PREF_P2P_PORT)
            } else {
                DesktopDataStore.setKey(DesktopDataStore.PREF_P2P_PORT, previousPort)
            }
        }
    }
}
