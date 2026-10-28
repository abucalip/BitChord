package com.music.bitchord.data.sources.navidrome

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The slice of the Subsonic-compatible JSON that BitChord reads.
 *
 * Every field but [SubsonicResponse.status] is optional: servers differ in what
 * they send, and a missing field is a gap in metadata, not a reason to drop the
 * row. Unknown fields are ignored by the client's `Json` configuration.
 */
@Serializable
internal data class SubsonicEnvelope(
    @SerialName("subsonic-response") val response: SubsonicResponse,
)

@Serializable
internal data class SubsonicResponse(
    val status: String = "",
    val version: String = "",
    val type: String? = null,
    val serverVersion: String? = null,
    val openSubsonic: Boolean = false,
    val error: SubsonicError? = null,
    val searchResult3: SearchResult3? = null,
    val artists: ArtistsIndex? = null,
    val albumList2: AlbumList2? = null,
    val artist: ArtistWithAlbums? = null,
    val album: AlbumWithSongs? = null,
    val song: NavidromeSong? = null,
    val playlists: PlaylistsIndex? = null,
    val playlist: PlaylistWithEntries? = null,
    val starred2: StarredSongs? = null,
)

@Serializable
internal data class SubsonicError(
    val code: Int = 0,
    val message: String = "",
)

@Serializable
internal data class SearchResult3(
    val song: List<NavidromeSong> = emptyList(),
)

/** One track as the server describes it. [id] is opaque and only meaningful to the server that issued it. */
@Serializable
data class NavidromeSong(
    val id: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumId: String? = null,
    val artistId: String? = null,
    /** Seconds. */
    val duration: Int? = null,
    val track: Int? = null,
    val year: Int? = null,
    val coverArt: String? = null,
    /** File extension of the original, e.g. `flac`. */
    val suffix: String? = null,
    val contentType: String? = null,
    /** kbps of the original. */
    val bitRate: Int? = null,
    val samplingRate: Int? = null,
    val bitDepth: Int? = null,
    val discNumber: Int? = null,
)

@Serializable
internal data class ArtistsIndex(val index: List<ArtistIndexEntry> = emptyList())

@Serializable
internal data class ArtistIndexEntry(val artist: List<NavidromeArtist> = emptyList())

@Serializable
internal data class AlbumList2(val album: List<NavidromeAlbum> = emptyList())

@Serializable
internal data class ArtistWithAlbums(val album: List<NavidromeAlbum> = emptyList())

@Serializable
internal data class AlbumWithSongs(val song: List<NavidromeSong> = emptyList())

@Serializable
internal data class PlaylistsIndex(val playlist: List<NavidromePlaylist> = emptyList())

/** The tracks this login has starred, as `getStarred2` returns them. Only songs are read. */
@Serializable
internal data class StarredSongs(val song: List<NavidromeSong> = emptyList())

/** A playlist with its tracks, as `getPlaylist` returns it. The tracks are in the server's order. */
@Serializable
internal data class PlaylistWithEntries(val entry: List<NavidromeSong> = emptyList())

/** An artist as listed by `getArtists`. [id] is opaque. */
@Serializable
data class NavidromeArtist(
    val id: String = "",
    val name: String = "",
    val albumCount: Int? = null,
    val coverArt: String? = null,
)

/** An album as listed by `getAlbumList2` / `getArtist`. [id] is opaque. */
@Serializable
data class NavidromeAlbum(
    val id: String = "",
    val name: String = "",
    val artist: String = "",
    val artistId: String? = null,
    val songCount: Int? = null,
    /** Seconds. */
    val duration: Int? = null,
    val year: Int? = null,
    val coverArt: String? = null,
)

/** A playlist as listed by `getPlaylists`. [id] is opaque. */
@Serializable
data class NavidromePlaylist(
    val id: String = "",
    val name: String = "",
    val songCount: Int? = null,
    /** Seconds. */
    val duration: Int? = null,
    val coverArt: String? = null,
)

/** What a successful ping tells us about the server. */
data class NavidromePing(
    val apiVersion: String,
    val serverType: String?,
    val serverVersion: String?,
)
