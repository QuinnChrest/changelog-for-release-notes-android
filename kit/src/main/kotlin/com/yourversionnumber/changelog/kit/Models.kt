package com.yourversionnumber.changelog.kit

import kotlinx.serialization.Serializable

// The shapes of Release Notes' /v1 API, as docs/API.md in
// jthingelstad/yvn-release-notes defines them. Fields arrive in snake_case and
// decode through Api.json. Every field a later API version adds is ignored
// until a type here names it, since /v1 only gains.

/** `GET /v1/me`: today's version and the next 14 days'. Never computed here. */
@Serializable
data class Me(
    val today: String,
    val version: String,
    val tz: String,
    /** The account's id, `acct_` and 32 hex characters, the same for every token of it (API 1.4.0). */
    val account: String? = null,
    // The spec leaves these four out when it has nothing to say; a missing one never stops a sign-in.
    val sendTime: String = "",
    val wroteToday: Boolean = false,
    val streak: Int = 0,
    val pausedUntil: String? = null,
    val status: String = "active",
    val showBadges: Boolean = true,
    val days: List<DayVersion> = emptyList(),
    val token: TokenInfo,
    val apiVersion: String,
) {
    /** The version for a local day the server listed, without the arithmetic. */
    fun versionOn(date: String): String? =
        if (date == today) version else days.firstOrNull { it.date == date }?.version
}

@Serializable
data class DayVersion(val date: String, val version: String)

@Serializable
data class TokenInfo(
    val name: String,
    val scopes: List<String>,
    val expiresAt: String? = null,
    /** The token's own record id, 32 hex characters: it grants nothing, and names the rows this token writes (API 1.4.0). */
    val id: String? = null,
)

/**
 * `POST /v1/notes`. `writtenAt` is the moment the words were typed, so a note
 * queued offline files to the day it was written.
 */
@Serializable
data class NoteRequest(
    val text: String? = null,
    val date: String? = null,
    val writtenAt: String? = null,
    /** With `media:write`. A note may be only its files. */
    val uploads: List<UploadRef>? = null,
    /** Where it was written, as Here named it. */
    val place: DevicePlace? = null,
)

/** A note this token wrote, as the API names it. */
@Serializable
data class NoteRef(
    val id: String,
    val date: String,
    val version: String,
    /** Opaque: send it back to edit, never compare or count it. */
    val revision: String,
    val token: String? = null,
    val files: Int? = null,
)

@Serializable
data class Unlocked(val key: String, val name: String)

@Serializable
data class NoteReceipt(val note: NoteRef, val unlocked: List<Unlocked>? = null)

/** `GET /v1/notes/{date}/{id}`, for a note this token wrote. */
@Serializable
data class NoteDetail(
    val id: String,
    val date: String,
    val text: String,
    val revision: String,
    val writtenAt: String? = null,
    val files: Int? = null,
)

/** `GET /v1/tags`: the pinned list, then the most used in the last 30 days. */
@Serializable
data class Tags(
    val pinned: List<String> = emptyList(),
    val recent: List<RecentTag> = emptyList(),
    val windowDays: Int? = null,
)

@Serializable
data class RecentTag(val tag: String, val count: Int, val lastUsed: String? = null)

/** `GET /v1/activity`: newest first, trimmed of words on the server. */
@Serializable
data class ActivityPage(val items: List<ActivityItem>, val next: String? = null)

/** One row of the activity log. Kinds are open-ended, so only what the app shows is named. */
@Serializable
data class ActivityItem(
    val id: String,
    val at: String,
    val kind: String,
    val via: String? = null,
    val client: String? = null,
    val token: String? = null,
    /** The writing token's id, on rows made since API 1.4.0: a name can be used again by a later token, an id never is. */
    val tokenId: String? = null,
    val day: String? = null,
    val version: String? = null,
    val note: String? = null,
    val files: Int? = null,
    val outcome: String? = null,
    val name: String? = null,
    val title: String? = null,
) {
    val family: String get() = kind.substringBefore('.')

    /** Whether this row was written through that token: by id when both have one, else by its name. */
    fun isFrom(by: TokenInfo): Boolean =
        via == "token" && (if (tokenId != null && by.id != null) tokenId == by.id else token == by.name)
}

/** The one error shape: `{"error": {"code", "message"}}`. */
@Serializable
internal data class ErrorBody(val error: Detail) {
    @Serializable
    data class Detail(
        val code: String,
        val message: String,
        val field: String? = null,
        val revision: String? = null,
    )
}
