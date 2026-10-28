package com.music.bitchord

import com.music.bitchord.data.sources.BrowsableMusicLibrary
import com.music.bitchord.data.sources.LibraryAlbum
import com.music.bitchord.data.sources.LibraryArtist
import com.music.bitchord.data.sources.LibraryPage
import com.music.bitchord.data.sources.LibraryPlaylist
import com.music.bitchord.data.sources.NavidromeSource
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceHealth
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.StreamRequest
import com.music.bitchord.data.sources.navidrome.NavidromeException
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

    private fun foundSong(id: String) = ok("\"song\":{\"id\":\"$id\",\"title\":\"t\",\"suffix\":\"flac\",\"duration\":200}")

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
    fun `a search row with cover art carries a reference to it, never its url`() = runTest {
        serverA.enqueue(ok("\"searchResult3\":{\"song\":[{\"id\":\"42\",\"title\":\"Song\",\"coverArt\":\"al-7\"}]}"))
        val result = source(config(serverA, "srv-a")).search("song").single()
        val thumb = result.thumbnailUrl!!
        assertEquals("navidrome-art:srv-a/al-7/150", thumb)
        assertFalse(thumb.contains("hunter2"))
        assertFalse(thumb.contains("u=alice"))
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
        serverA.enqueue(foundSong("42"))
        val first = s.stream("42", StreamRequest.Best)!!.url.toHttpUrl().queryParameter("s")
        val second = s.stream("42", StreamRequest.Best)!!.url.toHttpUrl().queryParameter("s")
        assertNotEquals(first, second)
    }

    @Test
    fun `a capped request asks the server to transcode`() = runTest {
        serverA.enqueue(foundSong("42"))
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
        serverA.enqueue(foundSong("1"))
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

    // ── Browsing ──────────────────────────────────────────────────────────

    @Test
    fun `a navidrome source is browsable and other sources are not forced to be`() {
        val s: Any = source(config(serverA, "a"))
        assertTrue(s is BrowsableMusicLibrary)
        assertFalse(com.music.bitchord.data.sources.MusicSource::class.java.interfaces
            .contains(BrowsableMusicLibrary::class.java))
    }

    @Test
    fun `artists map to library artists`() = runTest {
        serverA.enqueue(ok("\"artists\":{\"index\":[{\"artist\":[{\"id\":\"7\",\"name\":\"Seven\",\"albumCount\":2,\"coverArt\":\"ar-7\"}]}]}"))
        val artist = source(config(serverA, "a")).artists().getOrThrow().single()
        assertEquals(LibraryArtist("7", "Seven", 2, "ar-7"), artist)
    }

    @Test
    fun `a full page of albums has a next offset and a short one does not`() = runTest {
        val two = """{"id":"1","name":"A","artist":"X","artistId":"9","songCount":10,"year":2001,"coverArt":"al-1"},{"id":"2","name":"B","artist":"X"}"""
        serverA.enqueue(ok("\"albumList2\":{\"album\":[$two]}"))
        serverA.enqueue(ok("\"albumList2\":{\"album\":[{\"id\":\"3\",\"name\":\"C\"}]}"))
        val s = source(config(serverA, "a"))

        val first = s.albums(offset = 0, limit = 2).getOrThrow()
        assertEquals(2, first.nextOffset)
        assertEquals(LibraryAlbum("1", "A", "X", "9", 2001, 10, "al-1"), first.items[0])
        assertNull(first.items[1].year)

        val last = s.albums(offset = 2, limit = 2).getOrThrow()
        assertNull(last.nextOffset)
        assertEquals("2", serverA.takeRequest().let { serverA.takeRequest() }.requestUrl!!.queryParameter("offset"))
    }

    @Test
    fun `album tracks play back through this source and stay isolated by server`() = runTest {
        val body = "\"album\":{\"song\":[{\"id\":\"42\",\"title\":\"One\",\"track\":1}]}"
        serverA.enqueue(ok(body))
        serverB.enqueue(ok(body))
        val a = source(config(serverA, "id-a")).albumTracks("alb").getOrThrow().single()
        val b = source(config(serverB, "id-b")).albumTracks("alb").getOrThrow().single()
        assertEquals("id-a" to "42", SourceRegistry.parseTrackKey(a.videoId))
        assertEquals("id-b" to "42", SourceRegistry.parseTrackKey(b.videoId))
        assertEquals(1, a.trackNumber)
    }

    @Test
    fun `failures come back as results the screen can render`() = runTest {
        serverA.enqueue(MockResponse().setResponseCode(503))
        assertTrue(source(config(serverA, "a")).artists().isFailure)
        assertTrue(source(config(serverA, "a"), login = null).albums().isFailure)
        assertEquals(0, serverA.requestCount - 1)
    }

    @Test
    fun `artwork urls are authenticated and nothing is stored on the album`() = runTest {
        val s = source(config(serverA, "a"))
        val url = s.artworkUrl("al-1", 256)!!.toHttpUrl()
        assertEquals("/rest/getCoverArt", url.encodedPath)
        assertEquals("alice", url.queryParameter("u"))
        assertNull(source(config(serverA, "a"), login = null).artworkUrl("al-1", 256))

        serverA.enqueue(ok("\"albumList2\":{\"album\":[{\"id\":\"1\",\"name\":\"A\",\"coverArt\":\"al-1\"}]}"))
        val album = s.albums(limit = 5).getOrThrow().items.single()
        assertEquals("al-1", album.artworkRef)
        assertFalse(album.toString().contains("hunter2"))
        assertFalse(album.toString().contains("/rest/"))
    }

    @Test
    fun `library page slices an in-memory list`() {
        val all = (1..5).toList()
        assertEquals(LibraryPage(listOf(1, 2), 2), LibraryPage.of(all, 0, 2))
        assertEquals(LibraryPage(listOf(5), null), LibraryPage.of(all, 4, 2))
        assertEquals(LibraryPage(emptyList<Int>(), null), LibraryPage.of(all, 99, 2))
        assertEquals(LibraryPage(emptyList<Int>(), null), LibraryPage.of(emptyList<Int>(), 0, 2))
    }

    // ── Missing tracks ────────────────────────────────────────────────────

    @Test
    fun `a track the server no longer has is a miss and no stream url is built`() = runTest {
        serverA.enqueue(
            MockResponse().setBody("""{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":70,"message":"not found"}}}"""),
        )
        assertNull(source(config(serverA, "a")).stream("gone", StreamRequest.Best))
        val url = serverA.takeRequest().requestUrl!!
        assertEquals("/rest/getSong", url.encodedPath)
        assertEquals("gone", url.queryParameter("id"))
    }

    @Test
    fun `an empty song answer is also a miss`() = runTest {
        serverA.enqueue(ok("\"x\":1"))
        assertNull(source(config(serverA, "a")).stream("gone", StreamRequest.Best))
    }

    @Test
    fun `a track search has just returned is not checked again`() = runTest {
        val s = source(config(serverA, "a"))
        serverA.enqueue(ok("\"searchResult3\":{\"song\":[$song]}"))
        s.search("song")
        assertNotNull(s.stream("42", StreamRequest.Best))
        assertEquals("only the search should have reached the server", 1, serverA.requestCount)
    }

    @Test
    fun `an album's tracks are not checked again either`() = runTest {
        val s = source(config(serverA, "a"))
        serverA.enqueue(ok("\"album\":{\"song\":[$song]}"))
        s.albumTracks("alb").getOrThrow()
        assertNotNull(s.stream("42", StreamRequest.Best))
        assertEquals(1, serverA.requestCount)
    }

    @Test
    fun `the check fills in what a restored queue does not know`() = runTest {
        serverA.enqueue(foundSong("42"))
        val stream = source(config(serverA, "a")).stream("42", StreamRequest.Lossless)!!
        assertEquals("flac", stream.format.codec)
        assertEquals(200, stream.durationSec)
    }

    @Test
    fun `a check that cannot complete does not stop playback`() = runTest {
        serverA.enqueue(MockResponse().setResponseCode(503))
        val stream = source(config(serverA, "a")).stream("42", StreamRequest.Best)
        assertNotNull("a flaky check must not fail the track", stream)
    }

    @Test
    fun `an id is checked once and remembered`() = runTest {
        val s = source(config(serverA, "a"))
        serverA.enqueue(foundSong("42"))
        s.stream("42", StreamRequest.Best)
        s.stream("42", StreamRequest.Best)
        assertEquals(1, serverA.requestCount)
    }

    // ── Playlists ─────────────────────────────────────────────────────────

    @Test
    fun `playlists map to library playlists and an unnamed one shows its id`() = runTest {
        serverA.enqueue(
            ok("\"playlists\":{\"playlist\":[{\"id\":\"p1\",\"name\":\"Road\",\"songCount\":12,\"coverArt\":\"pl-1\"},{\"id\":\"p2\",\"name\":\"\"}]}"),
        )
        val playlists = source(config(serverA, "a")).playlists().getOrThrow()
        assertEquals(LibraryPlaylist("p1", "Road", 12, "pl-1"), playlists[0])
        assertEquals(LibraryPlaylist("p2", "p2", null, null), playlists[1])
    }

    @Test
    fun `playlist tracks play back through their own server and stay isolated`() = runTest {
        val body = "\"playlist\":{\"id\":\"p1\",\"entry\":[{\"id\":\"42\",\"title\":\"One\",\"track\":9}]}"
        serverA.enqueue(ok(body))
        serverB.enqueue(ok(body))
        val a = source(config(serverA, "id-a")).playlistTracks("p1").getOrThrow().single()
        val b = source(config(serverB, "id-b")).playlistTracks("p1").getOrThrow().single()
        assertEquals("id-a" to "42", SourceRegistry.parseTrackKey(a.videoId))
        assertEquals("id-b" to "42", SourceRegistry.parseTrackKey(b.videoId))
        assertNotEquals(a.videoId, b.videoId)
        // The playlist id goes only to the server it was listed from.
        assertEquals("p1", serverA.takeRequest().requestUrl!!.queryParameter("id"))
        assertEquals("p1", serverB.takeRequest().requestUrl!!.queryParameter("id"))
    }

    @Test
    fun `a playlist's tracks are not checked again on play`() = runTest {
        val s = source(config(serverA, "a"))
        serverA.enqueue(ok("\"playlist\":{\"id\":\"p1\",\"entry\":[$song]}"))
        s.playlistTracks("p1").getOrThrow()
        assertNotNull(s.stream("42", StreamRequest.Best))
        assertEquals(1, serverA.requestCount)
    }

    @Test
    fun `a playlist's tracks keep the server's order`() = runTest {
        serverA.enqueue(
            ok("\"playlist\":{\"id\":\"p1\",\"entry\":[{\"id\":\"9\",\"title\":\"B\",\"track\":1},{\"id\":\"4\",\"title\":\"A\",\"track\":2}]}"),
        )
        val ids = source(config(serverA, "a")).playlistTracks("p1").getOrThrow().map { SourceRegistry.parseTrackKey(it.videoId)?.second }
        assertEquals(listOf("9", "4"), ids)
    }

    @Test
    fun `playlists are not reached without a login or when the server fails`() = runTest {
        val signedOut = source(config(serverA, "a"), login = null)
        assertTrue(signedOut.playlists().isFailure)
        assertTrue(signedOut.playlistTracks("p1").isFailure)
        assertEquals("a signed-out source must not contact the server", 0, serverA.requestCount)

        serverA.enqueue(MockResponse().setResponseCode(503))
        assertTrue(source(config(serverA, "a")).playlists().isFailure)
    }

    // ── Favorites ────────────────────────────────────────────────────────

    @Test
    fun `favorites map to songs in this server's identity`() = runTest {
        serverA.enqueue(ok("\"starred2\":{\"song\":[{\"id\":\"42\",\"title\":\"Fave\"}]}"))
        val songs = source(config(serverA, "id-a")).favorites().getOrThrow()
        assertEquals(listOf("Fave"), songs.map { it.title })
        assertEquals("id-a" to "42", SourceRegistry.parseTrackKey(songs.single().videoId))
    }

    @Test
    fun `starring sends the server's own id to that server only`() = runTest {
        serverA.enqueue(ok("\"x\":1"))
        val result = source(config(serverA, "id-a")).setFavorite("42", favorite = true)
        assertTrue(result.isSuccess)

        val request = serverA.takeRequest().requestUrl!!
        assertEquals("/rest/star", request.encodedPath)
        assertEquals("42", request.queryParameter("id"))
        assertEquals(0, serverB.requestCount)
    }

    @Test
    fun `unstarring uses unstar`() = runTest {
        serverA.enqueue(ok("\"x\":1"))
        source(config(serverA, "id-a")).setFavorite("42", favorite = false)
        assertEquals("/rest/unstar", serverA.takeRequest().requestUrl!!.encodedPath)
    }

    @Test
    fun `a refused favorite is a failure result the screen can explain`() = runTest {
        serverA.enqueue(MockResponse().setBody(
            """{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":50,"message":"no"}}}""",
        ))
        val failure = source(config(serverA, "id-a")).setFavorite("42", favorite = true).exceptionOrNull()
        assertTrue(failure is NavidromeException.NotAllowed)
    }

    @Test
    fun `the same track starred on two servers stays two tracks`() = runTest {
        val body = "\"starred2\":{\"song\":[{\"id\":\"42\",\"title\":\"x\"}]}"
        serverA.enqueue(ok(body))
        serverB.enqueue(ok(body))
        val a = source(config(serverA, "id-a")).favorites().getOrThrow().single()
        val b = source(config(serverB, "id-b")).favorites().getOrThrow().single()
        assertNotEquals(a.videoId, b.videoId)
    }
}
