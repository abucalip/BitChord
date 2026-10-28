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
)

/** What a successful ping tells us about the server. */
data class NavidromePing(
    val apiVersion: String,
    val serverType: String?,
    val serverVersion: String?,
)
