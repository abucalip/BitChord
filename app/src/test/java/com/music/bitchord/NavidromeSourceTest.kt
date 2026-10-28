package com.music.bitchord

import com.music.bitchord.data.sources.NavidromeSource
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceHealth
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.StreamRequest
import com.music.bitchord.data.sources.navidrome.NavidromeLogin
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** [NavidromeSource] as the rest of the app sees it, against real HTTP servers. */
class NavidromeSourceTest {

    private lateinit var serverA: MockWebServer
    private lateinit var serverB: MockWebServer

    @Before
    fun setUp() {
        serverA = MockWebServer().also { it.start() }
        serverB = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        serverA.shutdown()
        serverB.shutdown()
    }

    private fun config(server: MockWebServer, id: String, label: String = "") = SourceConfig(
        id = id,
        kind = SourceKind.NAVIDROME,
        label = label,
        baseUrl = server.url("").toString().trimEnd('/'),
    )

    private fun source(
        config: SourceConfig,
        login: NavidromeLogin? = NavidromeLogin("alice", "hunter2"),
    ) = NavidromeSource(config, logins = { login })

    private fun ok(body: String) = MockResponse().setBody(
        """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome","serverVersion":"0.53.0",$body}}""",
    )

    private val song = """{"id":"42","title":"Song","artist":"Artist","album":"Album","duration":125,"track":7,
        "suffix":"flac","contentType":"audio/flac","bitRate":950,"samplingRate":96000,"bitDepth":24}"""

    // ── Kind ──────────────────────────────────────────────────────────────

    @Test
    fun `navidrome is a user-added kind that needs a server`() {
        assertTrue(SourceKind.NAVIDROME.isUserAdded)
        assertTrue(SourceKind.NAVIDROME.needsServer)
        assertTrue(SourceKind.NAVIDROME.canServeLossless)
        assertEquals(SourceKind.ADDON.rank, SourceKind.NAVIDROME.rank)
        assertFalse(SourceKind.NAVIDROME.worthPrefetching)
        assertTrue(SourceConfig(kind = SourceKind.NAVIDROME).enabled)
        assertFalse(SourceConfig(kind = SourceKind.NAVIDROME).isComplete)
    }

    // ── Health ────────────────────────────────────────────────────────────

    @Test
    fun `health is ok and names the server`() = runTest {
        serverA.enqueue(ok("\"x\":1"))
        val health = source(config(serverA, "a")).health()
        assertTrue(health.isOk)
        assertEquals("Navidrome v0.53.0 API 1.16.1", (health as SourceHealth.Ok).detail)
    }

    @Test
    fun `wrong credentials are rejected not unreachable`() = runTest {
        serverA.enqueue(
            MockResponse().setBody("""{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":40,"message":"x"}}}"""),
        )
        assertTrue(source(config(serverA, "a")).health() is SourceHealth.Rejected)
    }

    @Test
    fun `a server that is down is unreachable not rejected`() = runTest {
        val cfg = config(serverA, "a")
        serverA.shutdown()
        assertTrue(source(cfg).health() is SourceHealth.Unreachable)
    }

    @Test
    fun `a transient server error is unreachable`() = runTest {
        serverA.enqueue(MockResponse().setResponseCode(503))
        assertTrue(source(config(serverA, "a")).health() is SourceHealth.Unreachable)
    }

    @Test
    fun `no stored login is rejected without any request`() = runTest {
        val health = source(config(serverA, "a"), login = null).health()
        assertTrue(health is SourceHealth.Rejected)
        assertEquals(0, serverA.requestCount)
    }

    @Test
    fun `a blank address is rejected`() = runTest {
        assertTrue(source(SourceConfig(kind = SourceKind.NAVIDROME)).health() is SourceHealth.Rejected)
    }

    // ── Search and mapping ────────────────────────────────────────────────

    @Test
    fun `search maps a row into the existing song model`() = runTest {
        serverA.enqueue(ok("\"searchResult3\":{\"song\":[$song]}"))
        val result = source(config(serverA, "srv-a")).search("song").single()

        assertEquals(SourceRegistry.trackKey("srv-a", "42"), result.videoId)
        assertEquals("Song", result.title)
        assertEquals("Artist", result.artist)
        assertEquals("Album", result.albumName)
        assertEquals("2:05", result.durationText)
        assertEquals(7, result.trackNumber)
        assertEquals("LOSSLESS", result.sourceQuality)
        assertTrue(result.sourceHiRes)
        assertNull("artwork URLs would carry the login into persisted state", result.thumbnailUrl)
    }

    @Test
    fun `missing optional metadata does not crash mapping`() = runTest {
        serverA.enqueue(ok("\"searchResult3\":{\"song\":[{\"id\":\"1\"}]}"))
        val result = source(config(serverA, "a")).search("x").single()
        assertEquals("1", result.title)
        assertEquals("", result.artist)
        assertNull(result.albumName)
        assertNull(result.durationText)
        assertNull(result.sourceQuality)
        assertFalse(result.sourceHiRes)
    }

    @Test
    fun `search respects the limit and the server is asked for it`() = runTest {
        val many = (1..10).joinToString(",") { """{"id":"$it","title":"t$it"}""" }
        serverA.enqueue(ok("\"searchResult3\":{\"song\":[$many]}"))
        val results = source(config(serverA, "a")).search("t", limit = 3)
        assertEquals(3, results.size)
        assertEquals("3", serverA.takeRequest().requestUrl!!.queryParameter("songCount"))
    }

    @Test
    fun `blank queries and failures give an empty list`() = runTest {
        val s = source(config(serverA, "a"))
        assertTrue(s.search("   ").isEmpty())
        assertEquals(0, serverA.requestCount)
        serverA.enqueue(MockResponse().setResponseCode(500))
        assertTrue(s.search("x").isEmpty())
        assertTrue(source(config(serverA, "a"), login = null).search("x").isEmpty())
    }

    @Test
    fun `no credential reaches a song`() = runTest {
        serverA.enqueue(ok("\"searchResult3\":{\"song\":[$song]}"))
        val result = source(config(serverA, "a")).search("song").single()
        val dump = result.toString()
        assertFalse(dump.contains("hunter2"))
        assertFalse(dump.contains("u=alice"))
        assertFalse(dump.contains("/rest/"))
    }

    // ── Streams ───────────────────────────────────────────────────────────

    @Test
    fun `stream builds an authenticated url without storing anything`() = runTest {
        val s = source(config(serverA, "a"))
        serverA.enqueue(ok("\"searchResult3\":{\"song\":[$song]}"))
        s.search("song")

        val stream = s.stream("42", StreamRequest.Lossless)!!
        val url = stream.url.toHttpUrl()
        assertEquals(serverA.hostName, url.host)
        assertEquals("/rest/stream", url.encodedPath)
        assertEquals("42", url.queryParameter("id"))
        assertEquals("alice", url.queryParameter("u"))
        assertNotNull(url.queryParameter("t"))
        assertFalse(stream.url.contains("hunter2"))
        assertEquals("flac", stream.format.codec)
        assertEquals(950, stream.format.kbps)
        assertEquals(125, stream.durationSec)
        assertTrue(stream.headers.isEmpty())
    }

    @Test
    fun `each stream open gets a fresh salt`() = runTest {
        val s = source(config(serverA, "a"))
        val first = s.stream("42", StreamRequest.Best)!!.url.toHttpUrl().queryParameter("s")
        val second = s.stream("42", StreamRequest.Best)!!.url.toHttpUrl().queryParameter("s")
        assertNotEquals(first, second)
    }

    @Test
    fun `a capped request asks the server to transcode`() = runTest {
        val stream = source(config(serverA, "a")).stream("42", StreamRequest.Capped(128))!!
        assertEquals("128", stream.url.toHttpUrl().queryParameter("maxBitRate"))
        assertEquals(128, stream.format.kbps)
        assertTrue(stream.format.kbpsInferred)
    }

    @Test
    fun `stream without a login is a miss`() = runTest {
        assertNull(source(config(serverA, "a"), login = null).stream("42", StreamRequest.Best))
    }

    @Test
    fun `an edited password is used by the same instance`() = runTest {
        var login = NavidromeLogin("alice", "old")
        val s = NavidromeSource(config(serverA, "a"), logins = { login })
        val before = s.stream("1", StreamRequest.Best)!!.url.toHttpUrl()
        login = NavidromeLogin("bob", "new")
        val after = s.stream("1", StreamRequest.Best)!!.url.toHttpUrl()
        assertEquals("alice", before.queryParameter("u"))
        assertEquals("bob", after.queryParameter("u"))
    }

    // ── Isolation ─────────────────────────────────────────────────────────

    @Test
    fun `the same track id on two servers stays two tracks`() = runTest {
        serverA.enqueue(ok("\"searchResult3\":{\"song\":[{\"id\":\"42\",\"title\":\"From A\"}]}"))
        serverB.enqueue(ok("\"searchResult3\":{\"song\":[{\"id\":\"42\",\"title\":\"From B\"}]}"))
        val a = source(config(serverA, "id-a"))
        val b = source(config(serverB, "id-b"))

        val fromA = a.search("x").single()
        val fromB = b.search("x").single()

        assertNotEquals(fromA.videoId, fromB.videoId)
        assertEquals("id-a" to "42", SourceRegistry.parseTrackKey(fromA.videoId))
        assertEquals("id-b" to "42", SourceRegistry.parseTrackKey(fromB.videoId))
        // And each streams from its own server.
        assertEquals(serverA.port, a.stream("42", StreamRequest.Best)!!.url.toHttpUrl().port)
        assertEquals(serverB.port, b.stream("42", StreamRequest.Best)!!.url.toHttpUrl().port)
    }

    @Test
    fun `ids stay opaque including unusual ones`() = runTest {
        val id = "a::b|c d/é"
        serverA.enqueue(ok("\"searchResult3\":{\"song\":[{\"id\":\"$id\",\"title\":\"t\"}]}"))
        val song = source(config(serverA, "a")).search("t").single()
        assertEquals("a" to id, SourceRegistry.parseTrackKey(song.videoId))
    }
}
