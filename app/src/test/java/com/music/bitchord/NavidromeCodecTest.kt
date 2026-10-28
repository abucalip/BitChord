package com.music.bitchord

import com.music.bitchord.data.sources.navidrome.NavidromeClient
import com.music.bitchord.data.sources.navidrome.codecFromContentType
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** The codec of a transcoded stream, from the Content-Type it comes back with. */
class NavidromeCodecTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client() = NavidromeClient(
        rawBaseUrl = server.url("").toString(),
        username = "alice",
        password = "pw",
        salts = { "saltsalt" },
    )

    @Test
    fun contentTypesMapToTheirCodecIgnoringParameters() {
        assertEquals("mp3", codecFromContentType("audio/mpeg"))
        assertEquals("mp3", codecFromContentType("audio/mpeg; charset=binary"))
        assertEquals("aac", codecFromContentType("audio/aac"))
        assertEquals("aac", codecFromContentType("audio/mp4"))
        assertEquals("opus", codecFromContentType("audio/opus"))
        assertEquals("ogg", codecFromContentType("audio/ogg"))
        assertEquals("flac", codecFromContentType("AUDIO/FLAC"))
    }

    @Test
    fun unknownOrMissingContentTypesGiveNoCodec() {
        assertNull(codecFromContentType(null))
        assertNull(codecFromContentType("application/octet-stream"))
        assertNull(codecFromContentType("text/html"))
    }

    @Test
    fun transcodedCodecIsReadFromTheHeaderOfAOneByteRequest() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/mpeg").setBody("x"))
        val url = client().streamUrl("42", maxKbps = 192).getOrThrow()

        assertEquals("mp3", client().transcodedCodec(url))

        val request = server.takeRequest()
        assertEquals("bytes=0-0", request.getHeader("Range"))
    }

    @Test
    fun aFailedOrSilentServerLeavesTheCodecUnknown() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        val url = client().streamUrl("42", maxKbps = 192).getOrThrow()
        assertNull(client().transcodedCodec(url))
    }
}
