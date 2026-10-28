package com.music.bitchord

import com.music.bitchord.data.Http
import com.music.bitchord.data.sources.BrowsableMusicLibrary
import com.music.bitchord.data.sources.NavidromeSource
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceHealth
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.StreamRequest
import com.music.bitchord.data.sources.navidrome.NavidromeClient
import com.music.bitchord.data.sources.navidrome.NavidromeException
import com.music.bitchord.data.sources.navidrome.NavidromeLogin
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The Navidrome work, end to end, against a real server.
 *
 * Opt-in: skipped unless `NAVIDROME_URL`, `NAVIDROME_USER` and
 * `NAVIDROME_PASSWORD` are in the environment, so it never runs in CI and no
 * credential is stored in the repository. Strictly read-only: ping, search,
 * browse, and a byte-range read of one stream and one cover. Nothing here
 * prints a credential, token or URL.
 */
class NavidromeLiveTest {

    private lateinit var url: String
    private lateinit var login: NavidromeLogin

    @Before
    fun setUp() {
        val u = System.getenv("NAVIDROME_URL")
        val user = System.getenv("NAVIDROME_USER")
        val pass = System.getenv("NAVIDROME_PASSWORD")
        assumeTrue("NAVIDROME_* not set; skipping live test", !u.isNullOrBlank() && !user.isNullOrBlank() && !pass.isNullOrBlank())
        url = u!!
        login = NavidromeLogin(user!!, pass!!)
    }

    private fun client() = NavidromeClient(url, login.username, login.password)

    private fun source(id: String = "live-1") = NavidromeSource(
        SourceConfig(id = id, kind = SourceKind.NAVIDROME, baseUrl = url),
        logins = { login },
    )

    /** The first bytes of [target], fetched with a Range header so no full file is downloaded. */
    private fun peek(target: String, bytes: Int = 64): Triple<Int, String?, ByteArray> {
        val request = Request.Builder().url(target).header("Range", "bytes=0-${bytes - 1}").build()
        Http.client.newCall(request).execute().use { r ->
            val head = r.body?.source()?.let { s ->
                val buf = ByteArray(bytes)
                var n = 0
                while (n < bytes) { val k = s.read(buf, n, bytes - n); if (k <= 0) break; n += k }
                buf.copyOf(n)
            } ?: ByteArray(0)
            return Triple(r.code, r.header("Content-Type"), head)
        }
    }

    private fun sniff(head: ByteArray): String = when {
        head.size >= 4 && String(head, 0, 4, Charsets.ISO_8859_1) == "fLaC" -> "flac"
        head.size >= 3 && String(head, 0, 3, Charsets.ISO_8859_1) == "ID3" -> "mp3(id3)"
        head.size >= 2 && (head[0].toInt() and 0xFF) == 0xFF && (head[1].toInt() and 0xE0) == 0xE0 -> "mp3(frame)"
        head.size >= 4 && String(head, 0, 4, Charsets.ISO_8859_1) == "OggS" -> "ogg"
        head.size >= 8 && String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> "mp4/m4a"
        head.size >= 4 && String(head, 0, 4, Charsets.ISO_8859_1) == "RIFF" -> "wav"
        head.firstOrNull()?.toInt() == '{'.code -> "JSON (an error, not audio)"
        else -> "unknown"
    }

    // ── Connection ────────────────────────────────────────────────────────

    @Test
    fun `ping works over the configured address`() = runBlocking {
        val ping = client().ping().getOrThrow()
        println("LIVE ping: type=${ping.serverType} server=${ping.serverVersion} api=${ping.apiVersion}")
        assertTrue(ping.apiVersion.isNotBlank())
    }

    @Test
    fun `health reports ok with a detail line`() = runBlocking {
        val health = source().health()
        println("LIVE health: $health")
        assertTrue(health is SourceHealth.Ok)
    }

    @Test
    fun `a wrong password is an auth failure that does not echo the password`() = runBlocking {
        val bad = NavidromeClient(url, login.username, "definitely-not-the-password")
        val failure = bad.ping().exceptionOrNull()
        println("LIVE wrong password -> ${failure?.javaClass?.simpleName}: ${failure?.message}")
        assertTrue(failure is NavidromeException.Auth)
        assertFalse(failure!!.message.orEmpty().contains("definitely-not"))
        val health = NavidromeSource(
            SourceConfig(id = "bad", kind = SourceKind.NAVIDROME, baseUrl = url),
            logins = { NavidromeLogin(login.username, "definitely-not-the-password") },
        ).health()
        assertTrue("rejected, not unreachable: $health", health is SourceHealth.Rejected)
    }

    @Test
    fun `an unused port is a network failure not an auth failure`() = runBlocking {
        val host = url.toHttpUrl().host
        val failure = NavidromeClient("http://$host:9", login.username, login.password).ping().exceptionOrNull()
        println("LIVE closed port -> ${failure?.javaClass?.simpleName}")
        assertTrue(failure is NavidromeException.Network)
        assertFalse(failure!!.message.orEmpty().contains(login.password))
    }

    // ── Library ───────────────────────────────────────────────────────────

    @Test
    fun `artists albums and tracks can be browsed`() = runBlocking {
        val lib: BrowsableMusicLibrary = source()
        val artists = lib.artists().getOrThrow()
        println("LIVE artists: ${artists.size}")
        assertTrue("the library should have artists", artists.isNotEmpty())

        val page1 = lib.albums(0, 50).getOrThrow()
        println("LIVE albums page 1: ${page1.items.size} next=${page1.nextOffset}")
        assertTrue(page1.items.isNotEmpty())
        if (page1.items.size == 50) {
            assertEquals(50, page1.nextOffset)
            val page2 = lib.albums(50, 50).getOrThrow()
            println("LIVE albums page 2: ${page2.items.size}")
            assertTrue("page 2 must not repeat page 1", page2.items.none { p -> page1.items.any { it.id == p.id } })
        } else {
            assertEquals(null, page1.nextOffset)
        }

        val album = page1.items.first { (it.trackCount ?: 1) > 0 }
        val tracks = lib.albumTracks(album.id).getOrThrow()
        println("LIVE album '${album.title.take(30)}': ${tracks.size} tracks (server says ${album.trackCount})")
        assertTrue(tracks.isNotEmpty())
        val keys = tracks.map { SourceRegistry.parseTrackKey(it.videoId) }
        assertTrue(keys.all { it?.first == "live-1" })
        assertEquals("track keys are unique", keys.size, keys.toSet().size)
        val numbered = tracks.mapNotNull { it.trackNumber }
        assertEquals("tracks come back in track order", numbered.sorted(), numbered)

        val artist = artists.first { (it.albumCount ?: 1) > 0 }
        val albums = lib.artistAlbums(artist.id).getOrThrow()
        println("LIVE artist '${artist.name.take(30)}': ${albums.size} albums (server says ${artist.albumCount})")
        assertTrue(albums.isNotEmpty())
    }

    @Test
    fun `search finds a known artist and maps rows`() = runBlocking {
        val lib = source()
        val artist = lib.artists().getOrThrow().first { (it.albumCount ?: 0) > 0 }
        val results = lib.search(artist.name, limit = 10)
        println("LIVE search '${artist.name.take(30)}': ${results.size} rows")
        assertTrue(results.isNotEmpty())
        val first = results.first()
        assertTrue(first.title.isNotBlank())
        assertNotNull(SourceRegistry.parseTrackKey(first.videoId))
        assertFalse(first.toString().contains(login.password))
    }

    @Test
    fun `search handles non-ascii and punctuation without failing`() = runBlocking {
        val lib = source()
        for (q in listOf("é", "ñ", "ö", "&", "\"quoted\"", "a b c", "日本")) {
            val r = lib.search(q, limit = 5)
            println("LIVE search '$q' -> ${r.size}")
        }
    }

    @Test
    fun `a non-ascii name from the library is found by search`() = runBlocking {
        val lib = source()
        val artist = lib.artists().getOrThrow().first { a -> a.name.any { it.code > 127 } && (a.albumCount ?: 0) > 0 }
        val results = lib.search(artist.name, limit = 10)
        println("LIVE non-ascii search: ${results.size} rows (name has ${artist.name.length} chars)")
        assertTrue("a name that exists must be found; encoding may be broken", results.isNotEmpty())
        // The match may be on the album artist, so the row can credit someone else; what matters is that the query survived encoding.
    }

    // ── Playback ──────────────────────────────────────────────────────────

    @Test
    fun `an original stream returns real audio`() = runBlocking {
        val lib = source()
        val song = lib.albumTracks(lib.albums(0, 5).getOrThrow().items.first().id).getOrThrow().first()
        val (_, trackId) = SourceRegistry.parseTrackKey(song.videoId)!!
        val stream = source().stream(trackId, StreamRequest.Lossless)!!
        val (code, type, head) = peek(stream.url)
        println("LIVE stream original: http=$code type=$type sniff=${sniff(head)} declared=${stream.format.codec}")
        assertTrue("expected 200/206, got $code", code == 200 || code == 206)
        assertTrue("should be audio, was $type / ${sniff(head)}", type?.startsWith("audio/") == true || sniff(head) !in setOf("unknown", "JSON (an error, not audio)"))
    }

    @Test
    fun `a capped stream is transcoded`() = runBlocking {
        val lib = source()
        val song = lib.albumTracks(lib.albums(0, 5).getOrThrow().items.first().id).getOrThrow().first()
        val (_, trackId) = SourceRegistry.parseTrackKey(song.videoId)!!
        val stream = source().stream(trackId, StreamRequest.Capped(128))!!
        val (code, type, head) = peek(stream.url)
        println("LIVE stream capped 128: http=$code type=$type sniff=${sniff(head)}")
        assertTrue(code == 200 || code == 206)
        assertTrue(sniff(head) !in setOf("unknown", "JSON (an error, not audio)"))
    }

    @Test
    fun `each stream open uses a fresh salt and the server accepts every one`() = runBlocking {
        val lib = source()
        val song = lib.albumTracks(lib.albums(0, 5).getOrThrow().items.first().id).getOrThrow().first()
        val (_, trackId) = SourceRegistry.parseTrackKey(song.videoId)!!
        val a = source().stream(trackId, StreamRequest.Best)!!.url
        val b = source().stream(trackId, StreamRequest.Best)!!.url
        assertNotEquals(a.toHttpUrl().queryParameter("s"), b.toHttpUrl().queryParameter("s"))
        assertTrue(peek(a, 16).first in setOf(200, 206))
        assertTrue(peek(b, 16).first in setOf(200, 206))
    }

    @Test
    fun `a missing track is a clean miss instead of a json error handed to the player`() = runBlocking {
        val started = System.nanoTime()
        val stream = source().stream("no-such-track-id-000", StreamRequest.Best)
        println("LIVE missing track: stream=${if (stream == null) "null (miss)" else "a URL"} in ${(System.nanoTime() - started) / 1_000_000} ms")
        assertEquals(null, stream)
    }

    @Test
    fun `a track never seen this session is checked then streams with its real metadata`() = runBlocking {
        val lib = source()
        val song = lib.albumTracks(lib.albums(0, 5).getOrThrow().items.first().id).getOrThrow().first()
        val (_, trackId) = SourceRegistry.parseTrackKey(song.videoId)!!
        val fresh = source() // a new instance has seen nothing, like a queue restored after a restart
        val started = System.nanoTime()
        val stream = fresh.stream(trackId, StreamRequest.Lossless)!!
        println("LIVE restored-queue stream: codec=${stream.format.codec} kbps=${stream.format.kbps} duration=${stream.durationSec} in ${(System.nanoTime() - started) / 1_000_000} ms")
        assertNotNull(stream.format.codec)
        assertNotNull(stream.durationSec)
        assertTrue(peek(stream.url, 16).first in setOf(200, 206))
    }

    @Test
    fun `cover art is an authenticated image`() = runBlocking {
        val lib = source()
        val album = lib.albums(0, 50).getOrThrow().items.first { it.artworkRef != null }
        val art = lib.artworkUrl(album.artworkRef!!, 300)!!
        val (code, type, head) = peek(art, 16)
        println("LIVE cover: http=$code type=$type")
        assertEquals(200, code)
        assertTrue("expected an image, was $type", type?.startsWith("image/") == true)
        assertTrue(head.isNotEmpty())
    }

    // ── Isolation ─────────────────────────────────────────────────────────

    @Test
    fun `two registrations of one server stay separate tracks`() = runBlocking {
        val a = source("srv-a")
        val b = source("srv-b")
        val track = a.albumTracks(a.albums(0, 5).getOrThrow().items.first().id).getOrThrow().first()
        val sameFromB = b.albumTracks(b.albums(0, 5).getOrThrow().items.first().id).getOrThrow().first()
        assertNotEquals(track.videoId, sameFromB.videoId)
        assertEquals("srv-a", SourceRegistry.parseTrackKey(track.videoId)!!.first)
        assertEquals("srv-b", SourceRegistry.parseTrackKey(sameFromB.videoId)!!.first)
        assertEquals(SourceRegistry.parseTrackKey(track.videoId)!!.second, SourceRegistry.parseTrackKey(sameFromB.videoId)!!.second)
    }
}
