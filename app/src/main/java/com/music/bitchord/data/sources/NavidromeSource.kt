package com.music.bitchord.data.sources

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.navidrome.NavidromeAlbum
import com.music.bitchord.data.sources.navidrome.NavidromeClient
import com.music.bitchord.data.sources.navidrome.NavidromeCredentials
import com.music.bitchord.data.sources.navidrome.NavidromeException
import com.music.bitchord.data.sources.navidrome.NavidromeLogin
import com.music.bitchord.data.sources.navidrome.NavidromeSong
import kotlinx.coroutines.CancellationException
import java.util.Locale

/**
 * A [MusicSource] backed by one Navidrome server.
 *
 * Everything on the wire is [NavidromeClient]'s business; this class translates
 * between its vocabulary and the app's. The client is built per call from the
 * login as it is stored *now*, so an edited password takes effect without the
 * registry having to rebuild this instance, and nothing secret is held here.
 *
 * Track ids are the server's own and stay opaque. A [Song] from this source is
 * identified by [SourceRegistry.trackKey], which carries this source's config
 * id, so the same server id on two servers is two different tracks.
 */
class NavidromeSource(
    override val config: SourceConfig,
    private val logins: (String) -> NavidromeLogin? = NavidromeCredentials::get,
    private val clients: (String, NavidromeLogin) -> NavidromeClient =
        { url, login -> NavidromeClient(url, login.username, login.password) },
) : MusicSource, BrowsableMusicLibrary, SourceRegistry.ConfigBacked {

    override val configId: String get() = config.id
    override val kind: SourceKind get() = SourceKind.NAVIDROME
    override val displayName: String get() = config.displayName

    /**
     * What search last said about each track, by the server's id.
     *
     * [stream] has only an id to go on, and the codec and duration the player
     * wants to know about are in the row. Bounded and best-effort: a miss costs
     * a field of metadata, never the track.
     */
    private val rows = object : LinkedHashMap<String, NavidromeSong>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, NavidromeSong>) = size > MAX_ROWS
    }

    private fun client(): NavidromeClient? =
        logins(config.id)?.let { clients(config.baseUrl, it) }

    // ── Health ────────────────────────────────────────────────────────────

    override suspend fun health(): SourceHealth {
        if (config.baseUrl.isBlank()) return SourceHealth.Rejected("A server address is required")
        val client = client() ?: return SourceHealth.Rejected("Sign in to this server")
        return client.ping().fold(
            onSuccess = { ping ->
                SourceHealth.Ok(
                    listOfNotNull(
                        ping.serverType?.replaceFirstChar { it.titlecase(Locale.ROOT) },
                        ping.serverVersion?.let { "v$it" },
                        "API ${ping.apiVersion}",
                    ).joinToString(" "),
                )
            },
            onFailure = { failure ->
                if (failure is CancellationException) throw failure
                val reason = failure.message ?: "Could not reach the server"
                when (failure) {
                    // The server answered and said no: retyping is the fix.
                    is NavidromeException.Auth,
                    is NavidromeException.InvalidUrl,
                    is NavidromeException.Unsupported,
                    is NavidromeException.Malformed,
                    -> SourceHealth.Rejected(reason, failure)
                    // Anything else may pass: worth keeping enabled and retrying.
                    else -> {
                        TrackLog.w(TAG, "${config.displayName}: unreachable — $reason")
                        SourceHealth.Unreachable(reason, failure)
                    }
                }
            },
        )
    }

    // ── Search ────────────────────────────────────────────────────────────

    override suspend fun search(
        query: String,
        limit: Int,
        waitForAll: Boolean,
        request: StreamRequest?,
    ): List<Song> {
        if (query.isBlank()) return emptyList()
        val client = client() ?: return emptyList()
        val songs = client.searchSongs(query, count = limit).getOrElse { failure ->
            if (failure is CancellationException) throw failure
            TrackLog.w(TAG, "${config.displayName}: search failed — ${failure.message}")
            return emptyList()
        }
        return songs.take(limit).map { song ->
            synchronized(rows) { rows[song.id] = song }
            toSong(song)
        }
    }

    private fun toSong(song: NavidromeSong) = Song(
        videoId = SourceRegistry.trackKey(config.id, song.id),
        title = song.title.ifBlank { song.id },
        artist = song.artist,
        // Artwork is deliberately absent: Subsonic cover-art URLs carry the
        // login as query parameters, and a Song is persisted in queues and
        // history. Cover art arrives with browsing, through a loader that adds
        // the login per request.
        thumbnailUrl = null,
        albumName = song.album.ifBlank { null },
        durationText = song.duration?.let { "${it / 60}:${"%02d".format(Locale.ROOT, it % 60)}" },
        trackNumber = song.track,
        sourceQuality = ModuleSource.qualityTier("${song.suffix.orEmpty()} ${song.contentType.orEmpty()}"),
        sourceHiRes = (song.bitDepth ?: 0) > 16 || (song.samplingRate ?: 0) > 48_000,
    )

    // ── Browsing ──────────────────────────────────────────────────────────

    private suspend fun <T> browse(what: String, block: suspend (NavidromeClient) -> Result<T>): Result<T> {
        val client = client() ?: return Result.failure(NavidromeException.Auth("Sign in to this server"))
        return block(client).onFailure { failure ->
            if (failure is CancellationException) throw failure
            TrackLog.w(TAG, "${config.displayName}: $what failed — ${failure.message}")
        }
    }

    override suspend fun artists(): Result<List<LibraryArtist>> = browse("artist list") { client ->
        client.listArtists().map { list ->
            list.map { LibraryArtist(it.id, it.name.ifBlank { it.id }, it.albumCount, it.coverArt) }
        }
    }

    override suspend fun albums(offset: Int, limit: Int): Result<LibraryPage<LibraryAlbum>> =
        browse("album list") { client ->
            client.listAlbums(offset, limit).map { list ->
                // A full page means there may be more; a short one is the end.
                LibraryPage(list.map(::toAlbum), (offset + list.size).takeIf { list.size >= limit })
            }
        }

    override suspend fun artistAlbums(artistId: String): Result<List<LibraryAlbum>> =
        browse("artist albums") { client -> client.artistAlbums(artistId).map { it.map(::toAlbum) } }

    override suspend fun albumTracks(albumId: String): Result<List<Song>> =
        browse("album tracks") { client ->
            client.albumSongs(albumId).map { songs ->
                songs.map { song ->
                    synchronized(rows) { rows[song.id] = song }
                    toSong(song)
                }
            }
        }

    override fun artworkUrl(artworkRef: String, sizePx: Int): String? =
        client()?.coverArtUrl(artworkRef, sizePx)?.getOrNull()

    private fun toAlbum(album: NavidromeAlbum) = LibraryAlbum(
        id = album.id,
        title = album.name.ifBlank { album.id },
        artist = album.artist,
        artistId = album.artistId,
        year = album.year,
        trackCount = album.songCount,
        artworkRef = album.coverArt,
    )

    // ── Stream ────────────────────────────────────────────────────────────

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? {
        val client = client() ?: return null
        // A track search or browse has shown this session is known to exist. For
        // any other (a queue restored after a restart) ask the server first:
        // `stream` answers a missing id with HTTP 200 and a JSON error body,
        // which the player would try to decode as audio and fail on. Only a
        // definitive "not found" is a miss; if the check itself cannot be
        // completed (slow, offline) playback is still attempted, because
        // failing the track on a flaky check would be worse than the error the
        // player would have reported anyway.
        if (synchronized(rows) { rows[trackId] } == null) {
            client.getSong(trackId).fold(
                onSuccess = { found -> synchronized(rows) { rows[trackId] = found } },
                onFailure = { failure ->
                    if (failure is CancellationException) throw failure
                    if (failure is NavidromeException.NotFound) {
                        TrackLog.d(TAG, "${config.displayName}: track is no longer on the server")
                        return null
                    }
                    TrackLog.w(TAG, "${config.displayName}: could not check the track — ${failure.message}")
                },
            )
        }
        val capKbps = (request as? StreamRequest.Capped)?.maxKbps
        // Built at open time with a fresh salt, and handed straight to the
        // player: never stored, never logged.
        val url = client.streamUrl(trackId, capKbps).getOrNull() ?: return null
        val row = synchronized(rows) { rows[trackId] }
        val format = if (capKbps != null) {
            // The server transcodes; what it picks is its own business, so the
            // cap is the only thing known about the result.
            StreamFormat(kbps = capKbps, kbpsInferred = true)
        } else {
            StreamFormat(
                codec = row?.suffix?.lowercase(Locale.ROOT),
                kbps = row?.bitRate,
                sampleRateHz = row?.samplingRate,
                bitDepth = row?.bitDepth,
            )
        }
        return SourceStream(url = url, format = format, durationSec = row?.duration)
    }

    private companion object {
        const val TAG = "BitChord"
        const val MAX_ROWS = 256
    }
}
