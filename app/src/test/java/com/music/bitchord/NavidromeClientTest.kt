package com.music.bitchord

import com.music.bitchord.data.sources.navidrome.NavidromeClient
import com.music.bitchord.data.sources.navidrome.NavidromeException
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

/**
 * The Subsonic-compatible API as Navidrome speaks it, against a real HTTP
 * server whose answers are set per test.
 */
class NavidromeClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(
        path: String = "",
        salts: () -> String = NavidromeClient::randomSalt,
    ) = NavidromeClient(
        rawBaseUrl = server.url(path).toString(),
        username = "alice",
        password = "s3cret pass",
        salts = salts,
    )

    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(
        """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome","serverVersion":"0.53.0",$body}}""",
    )

    private fun failed(code: Int) = MockResponse().setResponseCode(200).setBody(
        """{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":$code,"message":"nope"}}}""",
    )

    // ── Authentication ────────────────────────────────────────────────────

    @Test
    fun `token is the md5 of password and salt`() {
        // Worked example from the Subsonic API documentation.
        assertEquals("26719a1196d2a940705a59634eb18eab", NavidromeClient.token("sesame", "c19b2d"))
    }

    @Test
    fun `requests carry a token and salt and never the password`() = runTest {
        server.enqueue(ok("\"x\":1"))
        client(salts = { "abc123" }).ping().getOrThrow()

        val request = server.takeRequest()
        val url = request.requestUrl!!
        assertEquals("/rest/ping", url.encodedPath)
        assertEquals("alice", url.queryParameter("u"))
        assertEquals("abc123", url.queryParameter("s"))
        assertEquals(NavidromeClient.token("s3cret pass", "abc123"), url.queryParameter("t"))
        assertEquals("json", url.queryParameter("f"))
        assertNull(url.queryParameter("p"))
        assertFalse(request.path.orEmpty().contains("s3cret"))
    }

    @Test
    fun `every request uses a fresh salt`() = runTest {
        server.enqueue(ok("\"x\":1"))
        server.enqueue(ok("\"x\":1"))
        val client = client()
        client.ping().getOrThrow()
        client.ping().getOrThrow()
        val first = server.takeRequest().requestUrl!!.queryParameter("s")
        val second = server.takeRequest().requestUrl!!.queryParameter("s")
        assertNotNull(first)
        assertNotEquals(first, second)
        assertEquals(32, first!!.length)
    }

    // ── Ping ──────────────────────────────────────────────────────────────

    @Test
    fun `ping reports the server`() = runTest {
        server.enqueue(ok("\"openSubsonic\":true"))
        val ping = client().ping().getOrThrow()
        assertEquals("1.16.1", ping.apiVersion)
        assertEquals("navidrome", ping.serverType)
        assertEquals("0.53.0", ping.serverVersion)
    }

    @Test
    fun `wrong credentials are an auth failure not a network failure`() = runTest {
        server.enqueue(failed(40))
        val failure = client().ping().exceptionOrNull()
        assertTrue(failure is NavidromeException.Auth)
        assertFalse(failure!!.message.orEmpty().contains("s3cret"))
    }

    @Test
    fun `a refused connection is a network failure`() = runTest {
        val dead = client()
        server.shutdown()
        assertTrue(dead.ping().exceptionOrNull() is NavidromeException.Network)
    }

    @Test
    fun `an html page is reported as not a subsonic server`() = runTest {
        server.enqueue(MockResponse().setBody("<html>login</html>"))
        assertTrue(client().ping().exceptionOrNull() is NavidromeException.Unsupported)
    }

    @Test
    fun `server errors and rate limits are distinguished`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setResponseCode(404))
        val client = client()
        assertTrue(client.ping().exceptionOrNull() is NavidromeException.ServerError)
        assertTrue(client.ping().exceptionOrNull() is NavidromeException.RateLimited)
        assertTrue(client.ping().exceptionOrNull() is NavidromeException.Unsupported)
    }

    @Test
    fun `an empty body is malformed`() = runTest {
        server.enqueue(MockResponse().setBody(""))
        assertTrue(client().ping().exceptionOrNull() is NavidromeException.Malformed)
    }

    // ── Search ────────────────────────────────────────────────────────────

    @Test
    fun `search parses songs and tolerates missing fields`() = runTest {
        server.enqueue(
            ok(
                """"searchResult3":{"song":[
                  {"id":"1","title":"Café del Mar","artist":"Énergie","album":"Été","duration":215,"track":3,"coverArt":"al-9","suffix":"flac","bitRate":900},
                  {"id":"2","title":"Bare"},
                  {"id":"","title":"No id"}
                ]}""",
            ),
        )
        val songs = client().searchSongs("cafe", count = 10, offset = 20).getOrThrow()

        assertEquals(listOf("1", "2"), songs.map { it.id })
        assertEquals("Café del Mar", songs[0].title)
        assertEquals(215, songs[0].duration)
        assertEquals("flac", songs[0].suffix)
        assertEquals("", songs[1].artist)
        assertNull(songs[1].duration)

        val url = server.takeRequest().requestUrl!!
        assertEquals("/rest/search3", url.encodedPath)
        assertEquals("cafe", url.queryParameter("query"))
        assertEquals("10", url.queryParameter("songCount"))
        assertEquals("20", url.queryParameter("songOffset"))
        assertEquals("0", url.queryParameter("artistCount"))
    }

    @Test
    fun `an empty result is an empty list`() = runTest {
        server.enqueue(ok("\"searchResult3\":{}"))
        assertTrue(client().searchSongs("nothing").getOrThrow().isEmpty())
    }

    @Test
    fun `ids stay opaque strings`() = runTest {
        server.enqueue(ok("\"searchResult3\":{\"song\":[{\"id\":\"0007\",\"title\":\"t\"}]}"))
        assertEquals("0007", client().searchSongs("t").getOrThrow().single().id)
    }

    // ── URLs ──────────────────────────────────────────────────────────────

    @Test
    fun `a reverse proxy path prefix is kept`() = runTest {
        server.enqueue(ok("\"x\":1"))
        client(path = "/music/").ping().getOrThrow()
        assertEquals("/music/rest/ping", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test
    fun `normalizeBase handles the forms people paste`() {
        assertEquals("https://nav.example.com/", NavidromeClient.normalizeBase("https://nav.example.com")!!.toString())
        assertEquals("https://nav.example.com/", NavidromeClient.normalizeBase("  https://nav.example.com//  ")!!.toString())
        assertEquals("https://nav.example.com/music", NavidromeClient.normalizeBase("https://nav.example.com/music/rest")!!.toString())
        assertEquals("http://192.168.1.5:4533/", NavidromeClient.normalizeBase("http://192.168.1.5:4533/?x=1#y")!!.toString())
    }

    @Test
    fun `normalizeBase rejects bad schemes malformed urls and embedded credentials`() {
        assertNull(NavidromeClient.normalizeBase("ftp://nav.example.com"))
        assertNull(NavidromeClient.normalizeBase("not a url"))
        assertNull(NavidromeClient.normalizeBase(""))
        assertNull(NavidromeClient.normalizeBase("https://alice:pw@nav.example.com"))
    }

    @Test
    fun `an invalid base url fails before any request`() = runTest {
        val bad = NavidromeClient("nonsense", "alice", "pw")
        assertTrue(bad.ping().exceptionOrNull() is NavidromeException.InvalidUrl)
        assertTrue(bad.streamUrl("1").exceptionOrNull() is NavidromeException.InvalidUrl)
    }

    @Test
    fun `redact keeps only scheme and host`() {
        assertEquals(
            "https://nav.example.com/***",
            NavidromeClient.redact("https://nav.example.com/rest/stream?u=alice&t=abc&s=def"),
        )
        assertEquals("***", NavidromeClient.redact("garbage"))
    }

    // ── Streams ───────────────────────────────────────────────────────────

    @Test
    fun `stream url asks for the original by default and carries fresh auth`() {
        val client = client()
        val first = client.streamUrl("42").getOrThrow()
        val second = client.streamUrl("42").getOrThrow()
        val url = first.toHttpUrl()

        assertEquals("/rest/stream", url.encodedPath)
        assertEquals("42", url.queryParameter("id"))
        assertEquals("raw", url.queryParameter("format"))
        assertNull(url.queryParameter("maxBitRate"))
        assertFalse(first.contains("s3cret"))
        assertNotEquals(first, second)
    }

    @Test
    fun `stream url honours a bitrate cap`() {
        val url = client().streamUrl("42", maxKbps = 128).getOrThrow().toHttpUrl()
        assertEquals("128", url.queryParameter("maxBitRate"))
        assertNull(url.queryParameter("format"))
    }

    @Test
    fun `track ids with special characters survive`() {
        val url = client().streamUrl("a&b=c d/é").getOrThrow().toHttpUrl()
        assertEquals("a&b=c d/é", url.queryParameter("id"))
    }

    // ── Browsing ──────────────────────────────────────────────────────────

    @Test
    fun `artists are flattened across index letters`() = runTest {
        server.enqueue(
            ok(
                """"artists":{"index":[
                  {"name":"A","artist":[{"id":"1","name":"Abba","albumCount":9,"coverArt":"ar-1"}]},
                  {"name":"B","artist":[{"id":"2","name":"Björk"},{"id":"","name":"No id"}]},
                  {"name":"C"}
                ]}""",
            ),
        )
        val artists = client().listArtists().getOrThrow()
        assertEquals(listOf("1", "2"), artists.map { it.id })
        assertEquals(9, artists[0].albumCount)
        assertEquals("Björk", artists[1].name)
        assertEquals("/rest/getArtists", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test
    fun `an empty library is an empty list not an error`() = runTest {
        server.enqueue(ok("\"artists\":{\"index\":[]}"))
        server.enqueue(ok("\"albumList2\":{}"))
        server.enqueue(ok("\"x\":1"))
        val client = client()
        assertTrue(client.listArtists().getOrThrow().isEmpty())
        assertTrue(client.listAlbums().getOrThrow().isEmpty())
        assertTrue(client.listArtists().getOrThrow().isEmpty())
    }

    @Test
    fun `albums are requested one page at a time`() = runTest {
        server.enqueue(ok("\"albumList2\":{\"album\":[{\"id\":\"9\",\"name\":\"Nine\",\"artist\":\"X\",\"songCount\":12,\"year\":1999}]}"))
        val albums = client().listAlbums(offset = 100, count = 25).getOrThrow()
        assertEquals("Nine", albums.single().name)
        assertEquals(12, albums.single().songCount)

        val url = server.takeRequest().requestUrl!!
        assertEquals("/rest/getAlbumList2", url.encodedPath)
        assertEquals("100", url.queryParameter("offset"))
        assertEquals("25", url.queryParameter("size"))
        assertEquals("alphabeticalByName", url.queryParameter("type"))
    }

    @Test
    fun `an artist's albums are fetched by id`() = runTest {
        server.enqueue(ok("\"artist\":{\"id\":\"1\",\"album\":[{\"id\":\"a1\",\"name\":\"One\"},{\"id\":\"a2\",\"name\":\"Two\"}]}"))
        assertEquals(listOf("a1", "a2"), client().artistAlbums("1").getOrThrow().map { it.id })
        assertEquals("1", server.takeRequest().requestUrl!!.queryParameter("id"))
    }

    @Test
    fun `album tracks come back in disc then track order`() = runTest {
        server.enqueue(
            ok(
                """"album":{"id":"a","song":[
                  {"id":"d2t1","title":"c","discNumber":2,"track":1},
                  {"id":"d1t2","title":"b","discNumber":1,"track":2},
                  {"id":"nonum1","title":"x"},
                  {"id":"d1t1","title":"a","discNumber":1,"track":1},
                  {"id":"nonum2","title":"y"}
                ]}""",
            ),
        )
        val ids = client().albumSongs("a").getOrThrow().map { it.id }
        assertEquals(listOf("d1t1", "d1t2", "nonum1", "nonum2", "d2t1"), ids)
    }

    @Test
    fun `a missing album is not found`() = runTest {
        server.enqueue(failed(70))
        assertTrue(client().albumSongs("gone").exceptionOrNull() is NavidromeException.NotFound)
    }

    @Test
    fun `cover art url is authenticated sized and fresh each time`() {
        val client = client()
        val first = client.coverArtUrl("al-9", 300).getOrThrow()
        val second = client.coverArtUrl("al-9", 300).getOrThrow()
        val url = first.toHttpUrl()
        assertEquals("/rest/getCoverArt", url.encodedPath)
        assertEquals("al-9", url.queryParameter("id"))
        assertEquals("300", url.queryParameter("size"))
        assertNotNull(url.queryParameter("t"))
        assertFalse(first.contains("s3cret"))
        assertNotEquals(first, second)
    }

    // ── getSong ───────────────────────────────────────────────────────────

    @Test
    fun `getSong returns the track`() = runTest {
        server.enqueue(ok("\"song\":{\"id\":\"7\",\"title\":\"Seven\",\"suffix\":\"mp3\",\"duration\":61}"))
        val song = client().getSong("7").getOrThrow()
        assertEquals("Seven", song.title)
        assertEquals("mp3", song.suffix)
        val url = server.takeRequest().requestUrl!!
        assertEquals("/rest/getSong", url.encodedPath)
        assertEquals("7", url.queryParameter("id"))
    }

    @Test
    fun `getSong for a missing track is not found`() = runTest {
        server.enqueue(failed(70))
        assertTrue(client().getSong("x").exceptionOrNull() is NavidromeException.NotFound)
    }

    @Test
    fun `getSong with no song in the answer is not found`() = runTest {
        server.enqueue(ok("\"x\":1"))
        assertTrue(client().getSong("x").exceptionOrNull() is NavidromeException.NotFound)
    }

    @Test
    fun `getSong gives up quickly on a server that never answers`() = runTest {
        server.enqueue(MockResponse().setBodyDelay(30, java.util.concurrent.TimeUnit.SECONDS).setBody("{}"))
        val started = System.nanoTime()
        val failure = client().getSong("x").exceptionOrNull()
        val seconds = (System.nanoTime() - started) / 1_000_000_000.0
        assertTrue("took ${seconds}s", seconds < 10)
        assertTrue(failure is NavidromeException.Network)
    }

    // ── Playlists ─────────────────────────────────────────────────────────

    @Test
    fun `playlists are listed in the server's order without blank ids`() = runTest {
        server.enqueue(
            ok(
                """"playlists":{"playlist":[
                  {"id":"p2","name":"Second","songCount":3,"duration":600,"coverArt":"pl-2"},
                  {"id":"","name":"No id"},
                  {"id":"p1","name":"First"}
                ]}""",
            ),
        )
        val playlists = client().listPlaylists().getOrThrow()
        assertEquals(listOf("p2", "p1"), playlists.map { it.id })
        assertEquals(3, playlists[0].songCount)
        assertEquals("pl-2", playlists[0].coverArt)
        assertNull(playlists[1].songCount)
        assertEquals("/rest/getPlaylists", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test
    fun `an account with no playlists gets an empty list`() = runTest {
        server.enqueue(ok("\"playlists\":{}"))
        server.enqueue(ok("\"x\":1"))
        assertTrue(client().listPlaylists().getOrThrow().isEmpty())
        assertTrue(client().listPlaylists().getOrThrow().isEmpty())
    }

    @Test
    fun `playlist tracks keep the server's order and are not re-sorted`() = runTest {
        server.enqueue(
            ok(
                """"playlist":{"id":"p1","name":"Mix","entry":[
                  {"id":"9","title":"Late","track":1,"discNumber":2},
                  {"id":"","title":"No id"},
                  {"id":"4","title":"Early","track":7,"discNumber":1},
                  {"id":"6","title":"Plain"}
                ]}""",
            ),
        )
        val songs = client().playlistSongs("p1").getOrThrow()
        assertEquals(listOf("9", "4", "6"), songs.map { it.id })
        val url = server.takeRequest().requestUrl!!
        assertEquals("/rest/getPlaylist", url.encodedPath)
        assertEquals("p1", url.queryParameter("id"))
    }

    @Test
    fun `an empty playlist is an empty list`() = runTest {
        server.enqueue(ok("\"playlist\":{\"id\":\"p1\",\"name\":\"Empty\"}"))
        assertTrue(client().playlistSongs("p1").getOrThrow().isEmpty())
    }

    @Test
    fun `a deleted playlist is not found`() = runTest {
        server.enqueue(failed(70))
        assertTrue(client().playlistSongs("gone").exceptionOrNull() is NavidromeException.NotFound)
    }

    @Test
    fun `a playlist call with the server down is a network failure`() = runTest {
        val dead = client()
        server.shutdown()
        assertTrue(dead.listPlaylists().exceptionOrNull() is NavidromeException.Network)
    }
}
