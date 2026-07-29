package com.kungsbackacarcommunity.app.update

/**
 * The server-held "which build is current" record, read from the flat
 * `config/appVersion` Firestore document (authenticated read, rules-gated;
 * writes only via the audited `admin.setAppVersion` callable).
 *
 * Everything is a `versionCode` — the monotonically increasing Android
 * integer — never a `versionName` string. Comparing version NAMES as text
 * is the classic way to get this wrong ("0.9.0" sorts after "0.10.0"), so
 * [latestVersionName] exists purely as display text for the dialog and is
 * never compared against anything.
 *
 * @property latestVersionCode versionCode of the newest build on Play.
 * @property latestVersionName display-only name for that build, or null.
 * @property minimumSupportedVersionCode oldest versionCode still supported;
 *   0 means nothing is unsupported, which is the default and keeps the
 *   blocking path inert.
 */
data class AppVersionConfig(
    val latestVersionCode: Int,
    val latestVersionName: String?,
    val minimumSupportedVersionCode: Int,
) {
    companion object {
        const val FIELD_LATEST_VERSION_CODE = "latestVersionCode"
        const val FIELD_LATEST_VERSION_NAME = "latestVersionName"
        const val FIELD_MINIMUM_SUPPORTED_VERSION_CODE = "minimumSupportedVersionCode"

        /**
         * Parses the stored document, or returns null when it is absent or
         * unusable.
         *
         * FAIL SAFE, DELIBERATELY: null propagates to "show nothing". A
         * missing document, a wrong type, a negative or fractional number —
         * every one of them means the app carries on exactly as it did
         * before this feature existed. The one thing this must never do is
         * turn a bad config value into a wall in front of a working app.
         *
         * [minimumSupportedVersionCode] gets an extra guard: a minimum ABOVE
         * [latestVersionCode] cannot be satisfied by any build a user could
         * install, so it is discarded (treated as 0) rather than obeyed.
         * The callable rejects that combination at the door too — this is
         * the second line of defence, for a value written by any other route.
         */
        fun fromStored(stored: Map<String, Any?>?): AppVersionConfig? {
            val fields = stored ?: return null
            val latest = fields[FIELD_LATEST_VERSION_CODE].asVersionCode() ?: return null
            val storedMinimum =
                fields[FIELD_MINIMUM_SUPPORTED_VERSION_CODE].asVersionCode() ?: 0
            val minimum = if (storedMinimum > latest) 0 else storedMinimum
            val name = (fields[FIELD_LATEST_VERSION_NAME] as? String)?.trim()?.takeIf {
                it.isNotEmpty()
            }
            return AppVersionConfig(
                latestVersionCode = latest,
                latestVersionName = name,
                minimumSupportedVersionCode = minimum,
            )
        }

        /**
         * Firestore hands integers back as [Long] (and a JS-written value can
         * arrive as [Double]), so accept any [Number] that is a whole,
         * finite, non-negative value inside the Int range — and nothing else.
         * A String "23" is NOT coerced: a value of the wrong type means the
         * document was written by something that does not understand the
         * contract, and guessing at its intent is how you brick an app.
         */
        private fun Any?.asVersionCode(): Int? {
            val value = (this as? Number)?.toDouble() ?: return null
            if (value.isNaN()) return null
            if (value < 0.0 || value > Int.MAX_VALUE.toDouble()) return null
            if (value % 1.0 != 0.0) return null
            return value.toInt()
        }
    }
}
