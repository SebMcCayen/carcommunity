package com.kungsbackacarcommunity.app.profile

import java.net.URI
import java.util.Locale

/** Read-only users/{uid}.clubBadge projection. It conveys affiliation, never authorization. */
@ConsistentCopyVisibility
data class ClubBadge private constructor(
    val clubId: String,
    val clubName: String,
    val logoUrl: String?,
) {
    val initials: String get() = clubName.split(Regex("\\s+"))
        .take(2).joinToString("") { word ->
            String(Character.toChars(word.codePointAt(0)))
        }.uppercase(Locale.ROOT).let { value ->
            value.substring(0, value.offsetByCodePoints(0, minOf(2, value.codePointCount(0, value.length))))
        }

    companion object {
        private val id = Regex("^[A-Za-z0-9_-]{1,128}$")

        /** Invalid affiliation is absent; an unsafe/missing image uses the club initials. */
        fun fromField(value: Any?): ClubBadge? {
            val fields = value as? Map<*, *> ?: return null
            if (fields.keys.any { it !in setOf("clubId", "clubName", "logoUrl") }) return null
            val clubId = fields["clubId"] as? String ?: return null
            val name = fields["clubName"] as? String ?: return null
            if (!id.matches(clubId) || name != name.trim() || name.length !in 3..60 ||
                name.any { Character.isISOControl(it) } || name.isBlank()) return null
            val logo = fields["logoUrl"]
            if (logo != null && logo !is String) return null
            return ClubBadge(clubId, name, trustedLogoUrl(logo as? String, clubId))
        }

        /** Only Firebase HTTPS logo objects for this club; no arbitrary external image hosts. */
        private fun trustedLogoUrl(value: String?, clubId: String): String? = runCatching {
            if (value.isNullOrBlank() || value.length > 2048) return null
            val uri = URI(value)
            if (uri.scheme != "https" || uri.host != "firebasestorage.googleapis.com" ||
                uri.rawUserInfo != null || uri.port != -1 || uri.rawFragment != null) return null
            val parts = uri.path.split('/')
            // /v0/b/{bucket}/o/{encoded object path}; URI.path decodes exactly once.
            if (parts.size != 9 || parts[1] != "v0" || parts[2] != "b" || parts[3].isBlank() ||
                parts[4] != "o" || parts[5] != "clubLogos" || parts[6] != clubId ||
                !id.matches(parts[7]) || !Regex("[A-Za-z0-9._-]{1,180}").matches(parts[8]) ||
                parts[8] in setOf(".", "..")) return null
            value
        }.getOrNull()
    }
}
