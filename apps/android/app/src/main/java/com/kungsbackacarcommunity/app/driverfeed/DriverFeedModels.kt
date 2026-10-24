package com.kungsbackacarcommunity.app.driverfeed

import java.net.URI

const val DRIVER_FEED_PAGE_SIZE = 20L

data class DriverCursor(val seconds: Long, val nanoseconds: Int, val id: String)
data class DriverPage<T>(val items: List<T>, val next: DriverCursor?)
data class DriverPost(
    val id: String,
    val authorUid: String,
    val author: String,
    val handle: String,
    val text: String,
    val createdAtMillis: Long?,
    val mediaUrls: List<String>,
    val likes: Int,
    val replies: Int,
) {
    companion object {
        fun decode(id: String, data: Map<*, *>): DriverPost? {
            if (!validDriverId(id)) return null
            val uid = data["authorUid"] as? String ?: return null
            if (!validDriverId(uid)) return null
            return DriverPost(
                id, uid, data["authorName"] as? String ?: "",
                data["authorHandle"] as? String ?: "", data["text"] as? String ?: "",
                (data["createdAtMillis"] as? Number)?.toLong(),
                (data["mediaUrls"] as? List<*>)?.filterIsInstance<String>()
                    ?.filter(::safeDriverMedia)?.take(4).orEmpty(),
                (data["likeCount"] as? Number)?.toInt()?.coerceAtLeast(0) ?: 0,
                (data["replyCount"] as? Number)?.toInt()?.coerceAtLeast(0) ?: 0,
            )
        }
    }
}

data class DriverComment(
    val id: String, val authorUid: String, val author: String, val text: String,
    val createdAtMillis: Long?,
)

data class DriverDetailPage(val post: DriverPost, val comments: DriverPage<DriverComment>)

enum class DriverFeedFailure { ERROR, UNAVAILABLE, ONBOARDING, CANONICAL_ONBOARDING, RESTRICTED }
class DriverFeedException(val failure: DriverFeedFailure) : Exception()

fun validDriverId(value: String): Boolean =
    value.isNotBlank() && value.length <= 128 && '/' !in value && value != "." && value != ".."

internal fun safeDriverMedia(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null
}.getOrDefault(false)

data class DriverFeedStrings(val lookup: (String) -> String) {
    operator fun get(suffix: String): String = lookup("driver.feed.$suffix")
}
