package com.music.bitchord

import android.content.SharedPreferences
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.AudioQuality
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.StreamRequest
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.data.sources.navidrome.NavidromeCredentials
import com.music.bitchord.data.sources.navidrome.NavidromeLogin
import kotlinx.coroutines.runBlocking
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
 * What happens to a Navidrome source and the tracks queued from it when it is
 * edited, disabled, removed or cannot be reached.
 */
class NavidromeLifecycleTest {

    private lateinit var a: MockWebServer
    private lateinit var b: MockWebServer
    private val ids = mutableListOf<String>()

    @Before
    fun setUp() {
        a = MockWebServer().also { it.start() }
        b = MockWebServer().also { it.start() }
        NavidromeCredentials.installForTest(MemoryPrefs())
    }

    @After
    fun tearDown() {
        ids.forEach(SourceRegistry::remove)
        a.shutdown()
        b.shutdown()
    }

    private fun add(server: MockWebServer, id: String, label: String = "", login: Boolean = true): SourceConfig {
        val config = SourceConfig(
            id = id,
            kind = SourceKind.NAVIDROME,
            label = label,
            baseUrl = server.url("").toString().trimEnd('/'),
        )
        ids += id
        SourceRegistry.add(config)
        if (login) assertTrue(NavidromeCredentials.save(id, NavidromeLogin("alice", "pw-$id")))
        return config
    }

    private val target = TrackMatcher.Target("Song", "Artist", durationSec = 125)

    private fun found(id: String) = MockResponse().setBody(
        """{"subsonic-response":{"status":"ok","version":"1.16.1","song":{"id":"$id","title":"Song"}}}""",
    )

    private fun search(id: String) = MockResponse().setBody(
        """{"subsonic-response":{"status":"ok","version":"1.16.1","searchResult3":{"song":[
          {"id":"$id","title":"Song","artist":"Artist","duration":125,"suffix":"flac"}]}}}""",
    )

    // ── Streaming quality ─────────────────────────────────────────────────

    /**
     * Found on an emulator: below Lossless the ceiling filtered Navidrome out of
     * the playback sources, and because a Navidrome track is never answered by
     * another source, every track from the server failed with "No enabled source
     * could serve …". The server can transcode, so it has to stay in.
     */
    @Test
    fun `a Navidrome track still resolves under every ceiling below Lossless`() = runBlocking {
        add(a, "srv-a")
        a.enqueue(found("42"))
        val cellular = AppSettings.audioQualityCellular.value
        val metered = AppSettings.meteredConnection.value
        try {
            AppSettings.meteredConnection.value = true
            for (quality in listOf(AudioQuality.HIGH, AudioQuality.MEDIUM, AudioQuality.LOW)) {
                AppSettings.audioQualityCellular.value = quality
                val stream = SourceResolver.resolve("srv-a", "42", target)
                assertNotNull("$quality: the track must still resolve", stream)
                val url = stream!!.url.toHttpUrl()
                if (quality.maxKbps == Int.MAX_VALUE) {
                    // No cap asked for, so the original file.
                    assertEquals("$quality", "raw", url.queryParameter("format"))
                } else {
                    // A cap: the server transcodes to it.
                    assertEquals("$quality", quality.maxKbps.toString(), url.queryParameter("maxBitRate"))
                }
            }
        } finally {
            AppSettings.audioQualityCellular.value = cellular
            AppSettings.meteredConnection.value = metered
        }
    }

    // ── Tracks stay with their own server ─────────────────────────────────

    @Test
    fun `a queued track resolves against its own server even when ids collide`() = runBlocking {
        add(a, "srv-a")
        add(b, "srv-b")
        a.enqueue(found("42"))
        b.enqueue(found("42"))
        val fromA = SourceResolver.resolve("srv-a", "42", target)!!
        val fromB = SourceResolver.resolve("srv-b", "42", target)!!
        assertEquals(a.port, fromA.url.toHttpUrl().port)
        assertEquals(b.port, fromB.url.toHttpUrl().port)
        assertEquals("srv-a", fromA.sourceConfigId)
        assertEquals("srv-b", fromB.sourceConfigId)
    }

    @Test
    fun `a disabled server is skipped and nothing else answers for it`() = runBlocking {
        add(a, "srv-a")
        add(b, "srv-b")
        b.enqueue(search("99"))
        SourceRegistry.setEnabled("srv-a", false)

        assertNull(SourceResolver.resolve("srv-a", "42", target))
        assertEquals("no request may reach the disabled server", 0, a.requestCount)
        assertEquals("another server must not be searched in its place", 0, b.requestCount)
    }

    @Test
    fun `a track deleted from its server is skipped, not replaced by another server's copy`() = runBlocking {
        add(a, "srv-a")
        add(b, "srv-b")
        a.enqueue(
            MockResponse().setBody("""{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":70,"message":"x"}}}"""),
        )
        b.enqueue(search("99"))
        assertNull(SourceResolver.resolve("srv-a", "gone", target))
        assertEquals("the other server must not be searched", 0, b.requestCount)
    }

    @Test
    fun `an enabled server that cannot serve the track is not replaced by another`() = runBlocking {
        add(a, "srv-a", login = false)
        add(b, "srv-b")
        b.enqueue(search("99"))
        assertNull(SourceResolver.resolve("srv-a", "42", target))
        assertEquals(0, b.requestCount)
    }

    // ── Editing ───────────────────────────────────────────────────────────

    @Test
    fun `re-keying keeps place and state, retires the old id and its login`() = runBlocking {
        add(a, "first")
        add(a, "moved")
        add(a, "last")
        SourceRegistry.setEnabled("moved", false)

        val replacement = SourceConfig(
            id = "moved-2",
            kind = SourceKind.NAVIDROME,
            baseUrl = b.url("").toString().trimEnd('/'),
        )
        ids += "moved-2"
        assertTrue(NavidromeCredentials.save("moved-2", NavidromeLogin("alice", "new")))
        SourceRegistry.rekey("moved", replacement)

        val order = SourceRegistry.configs.value.map { it.id }.filter { it in setOf("first", "moved", "moved-2", "last") }
        assertEquals(listOf("first", "moved-2", "last"), order)
        assertFalse("the on/off state carries over", SourceRegistry.config("moved-2")!!.enabled)
        assertNull(SourceRegistry.config("moved"))
        assertNull("the old login must not linger", NavidromeCredentials.get("moved"))
        assertNotNull(NavidromeCredentials.get("moved-2"))
    }

    @Test
    fun `after the address changes old ids are never sent to the new server`() = runBlocking {
        add(a, "old")
        val replacement = SourceConfig(
            id = "new",
            kind = SourceKind.NAVIDROME,
            baseUrl = b.url("").toString().trimEnd('/'),
        )
        ids += "new"
        NavidromeCredentials.save("new", NavidromeLogin("alice", "pw"))
        SourceRegistry.rekey("old", replacement)

        // The new server knows the song under a different id. Whatever the
        // old id was, it is matched by metadata and the stream asked for is
        // the new server's own id — never the stale one.
        b.enqueue(search("NEW-9"))
        val stream = SourceResolver.resolve("old", "OLD-1", target)!!
        assertEquals("NEW-9", stream.url.toHttpUrl().queryParameter("id"))
        assertEquals(b.port, stream.url.toHttpUrl().port)
        assertEquals(0, a.requestCount)
    }

    @Test
    fun `rekey ignores unknown, identical and built-in ids`() {
        add(a, "keep")
        SourceRegistry.rekey("nope", SourceConfig(id = "x", kind = SourceKind.NAVIDROME))
        SourceRegistry.rekey("keep", SourceConfig(id = "keep", kind = SourceKind.NAVIDROME))
        assertNotNull(SourceRegistry.config("keep"))
        assertNull(SourceRegistry.config("x"))
    }

    @Test
    fun `removing a server deletes its login`() {
        add(a, "gone")
        assertNotNull(NavidromeCredentials.get("gone"))
        SourceRegistry.remove("gone")
        assertNull(NavidromeCredentials.get("gone"))
    }

    // ── Credentials ───────────────────────────────────────────────────────

    @Test
    fun `saving or deleting a login bumps the revision the sources screen watches`() {
        val before = NavidromeCredentials.revision.value
        NavidromeCredentials.save("rev", NavidromeLogin("u", "p"))
        assertTrue(NavidromeCredentials.revision.value > before)
        val mid = NavidromeCredentials.revision.value
        NavidromeCredentials.delete("rev")
        assertTrue(NavidromeCredentials.revision.value > mid)
    }

    @Test
    fun `a password is refused when only plain storage is available`() {
        NavidromeCredentials.installForTest(MemoryPrefs(), encrypted = false)
        assertFalse(NavidromeCredentials.canStore())
        assertFalse(NavidromeCredentials.save("plain", NavidromeLogin("u", "p")))
        assertNull(NavidromeCredentials.get("plain"))
    }

    @Test
    fun `a login never prints its password`() {
        assertFalse(NavidromeLogin("alice", "hunter2").toString().contains("hunter2"))
    }

    // ── Labels ────────────────────────────────────────────────────────────

    @Test
    fun `servers on one host are told apart by port and path`() {
        fun name(url: String, label: String = "") =
            SourceConfig(kind = SourceKind.NAVIDROME, baseUrl = url, label = label).displayName

        assertEquals("music.example.com", name("https://music.example.com"))
        assertEquals("music.example.com:4533", name("https://music.example.com:4533"))
        assertEquals("music.example.com/family", name("https://music.example.com/family/"))
        assertEquals("192.168.1.5:4533", name("http://192.168.1.5:4533"))
        assertNotEquals(name("https://h.example/a"), name("https://h.example/b"))
        assertEquals("Home", name("https://music.example.com", label = "Home"))
        assertEquals("Navidrome", name(""))
    }
}

/** A [SharedPreferences] held in memory, for tests that need a store without Android. */
private class MemoryPrefs : SharedPreferences {
    private val data = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = data.toMutableMap()
    override fun getString(key: String, defValue: String?) = data[key] as? String ?: defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?) =
        @Suppress("UNCHECKED_CAST") (data[key] as? MutableSet<String>) ?: defValues
    override fun getInt(key: String, defValue: Int) = data[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long) = data[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float) = data[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = data[key] as? Boolean ?: defValue
    override fun contains(key: String) = key in data
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val staged = mutableMapOf<String, Any?>()
        private val removed = mutableSetOf<String>()
        private var clear = false
        override fun putString(key: String, value: String?) = apply { staged[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { staged[key] = values }
        override fun putInt(key: String, value: Int) = apply { staged[key] = value }
        override fun putLong(key: String, value: Long) = apply { staged[key] = value }
        override fun putFloat(key: String, value: Float) = apply { staged[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { staged[key] = value }
        override fun remove(key: String) = apply { removed += key }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            if (clear) data.clear()
            removed.forEach(data::remove)
            data.putAll(staged)
            return true
        }
        override fun apply() { commit() }
    }
}
