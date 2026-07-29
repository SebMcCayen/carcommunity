package com.kungsbackacarcommunity.app.profile

/** The social platforms a member may link from their public profile. */
enum class SocialPlatform {
    FACEBOOK,
    INSTAGRAM,
    YOUTUBE,
}

/**
 * The canonical social handles carried on `users/{uid}`.
 *
 * A null entry means "not set" — the field is ABSENT from the Firestore
 * document rather than stored as an empty string, so "unset" has exactly one
 * representation on the wire (see FirebaseProfileRepository.updateProfile,
 * which writes FieldValue.delete()).
 */
data class SocialHandles(
    val facebook: String? = null,
    val instagram: String? = null,
    val youtube: String? = null,
) {
    fun handle(platform: SocialPlatform): String? =
        when (platform) {
            SocialPlatform.FACEBOOK -> facebook
            SocialPlatform.INSTAGRAM -> instagram
            SocialPlatform.YOUTUBE -> youtube
        }

    companion object {
        val EMPTY = SocialHandles()
    }
}

/** A handle that survived validation, paired with the URL it renders to. */
data class SocialLink(
    val platform: SocialPlatform,
    val handle: String,
    val url: String,
)

/**
 * Parsing, validation and canonicalisation of member-supplied social links.
 *
 * WHY A HANDLE IS STORED AND NOT A URL
 * ------------------------------------
 * A link a member types and every other member then taps is a phishing vector:
 * if the stored value were a URL, "Instagram" on someone's profile could point
 * at anything, and keeping it honest would mean re-deriving a host allowlist on
 * every read, in every client, forever. Storing the HANDLE removes the class of
 * bug instead of policing it — the host is a compile-time constant in
 * [canonicalUrl] and there is no field in the document a foreign host could
 * even be written into. [parse] therefore ACCEPTS a URL as input (members
 * paste them) but never keeps one.
 *
 * The stored shape is re-validated on the way OUT as well ([links]): a handle
 * that does not match its platform pattern exactly renders nothing at all,
 * so a document written before the Security Rules below were deployed — or by
 * any future Admin SDK path — still cannot produce a surprising link.
 *
 * SERVER-SIDE ENFORCEMENT: this object is UX only. The authority is
 * firebase/firestore.rules (validFacebookHandle / validInstagramHandle /
 * validYoutubeHandle), which applies the SAME character patterns and length
 * bounds to the resulting document. Change one and you must change the other;
 * functions/src/__tests__/security-rules.emulator.test.ts is what proves the
 * rules half.
 */
object SocialLinks {

    /** Why a member's input was refused. Each maps to one message in the UI. */
    enum class Error {
        /** The link pointed at a host that is not the platform's. */
        FOREIGN_HOST,

        /** The right host, but not a profile link (a post, a video, a group). */
        UNSUPPORTED_LINK,

        /** Not a handle and not a link we can read. */
        MALFORMED,

        /** A plausible handle, but longer than the platform allows. */
        TOO_LONG,
    }

    sealed interface Parsed {
        /** Blank input — the member is CLEARING the field, not failing it. */
        data object Empty : Parsed

        data class Handle(val value: String) : Parsed

        data class Rejected(val error: Error) : Parsed
    }

    /**
     * Longest input accepted before parsing. A profile URL with a query string
     * fits comfortably; anything beyond is not a link a member typed.
     */
    const val MAX_INPUT_LENGTH = 300

    /**
     * Normalises one member-supplied value to its canonical handle.
     *
     * Accepts, for Instagram: `sebmccayen`, `@sebmccayen`,
     * `instagram.com/sebmccayen`, `https://www.instagram.com/sebmccayen/`,
     * `https://instagram.com/SebMcCayen?igshid=x` — all yield `sebmccayen`.
     */
    fun parse(platform: SocialPlatform, raw: String): Parsed {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Parsed.Empty
        if (trimmed.length > MAX_INPUT_LENGTH) return Parsed.Rejected(Error.TOO_LONG)
        // Control characters, whitespace and zero-width/format characters are
        // rejected outright: none can occur in a handle or in a URL a member
        // legitimately copied, and they are the usual way a homograph or a
        // line-break injection is smuggled past a later check.
        if (trimmed.any { it.isRejectedChar() }) return Parsed.Rejected(Error.MALFORMED)

        val spec = specFor(platform)
        var value = trimmed
        var absolute = false

        val schemeEnd = value.indexOf("://")
        if (schemeEnd >= 0) {
            val scheme = value.substring(0, schemeEnd).lowercase()
            // javascript://, data://, intent:// — anything that is not plain web.
            if (scheme != "http" && scheme != "https") return Parsed.Rejected(Error.MALFORMED)
            value = value.substring(schemeEnd + 3)
            absolute = true
        } else if (value.startsWith("//")) {
            // Scheme-relative: still names a host, so it is treated as one.
            value = value.substring(2)
            absolute = true
        } else if (value.contains(':')) {
            // javascript:alert(1), mailto:, intent:, or a bare host:port. None
            // of these is a handle and none is a profile link we accept.
            return Parsed.Rejected(Error.MALFORMED)
        }
        if (value.isEmpty()) return Parsed.Rejected(Error.MALFORMED)

        val slash = value.indexOf('/')
        val authority = if (slash >= 0) value.substring(0, slash) else value
        val remainder = if (slash >= 0) value.substring(slash + 1) else ""

        // A handle can never contain '/', so a slash (or an explicit scheme)
        // means the leading segment is a HOST and must survive the allowlist.
        return if (absolute || slash >= 0) {
            parseHosted(platform, spec, authority, remainder)
        } else {
            parseBare(spec, authority)
        }
    }

    /**
     * The canonical https URL for a stored handle, or null when [handle] is not
     * exactly canonical for [platform].
     *
     * The host is a constant here — that is the whole point of storing handles.
     * The handle is re-checked rather than repaired: a value that does not match
     * is refused, never coerced into something that happens to parse.
     */
    fun canonicalUrl(platform: SocialPlatform, handle: String): String? {
        val spec = specFor(platform)
        if (handle.length !in spec.minLength..spec.maxLength) return null
        if (!spec.pattern.matches(handle)) return null
        return when (platform) {
            SocialPlatform.FACEBOOK -> "https://www.facebook.com/$handle"
            SocialPlatform.INSTAGRAM -> "https://www.instagram.com/$handle"
            // YouTube addresses accounts as @handle; the '@' belongs to the URL,
            // not to the stored value.
            SocialPlatform.YOUTUBE -> "https://www.youtube.com/@$handle"
        }
    }

    /**
     * The renderable links for [handles], in a fixed platform order.
     *
     * An EMPTY list is the signal that no social row should be drawn at all —
     * the profile surfaces render nothing (no placeholder, no empty row) when
     * this is empty, which is also how "member has set none" is tested.
     */
    fun links(handles: SocialHandles): List<SocialLink> =
        SocialPlatform.entries.mapNotNull { platform ->
            val handle = handles.handle(platform)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val url = canonicalUrl(platform, handle) ?: return@mapNotNull null
            SocialLink(platform, handle, url)
        }

    // ---- internals ---------------------------------------------------------

    private fun parseHosted(
        platform: SocialPlatform,
        spec: Spec,
        authority: String,
        path: String,
    ): Parsed {
        // Userinfo (`https://instagram.com@evil.example/x`) and an explicit port
        // are refused before the allowlist is consulted, so the allowlist only
        // ever sees a bare hostname and cannot be talked past by either.
        if (authority.contains('@')) return Parsed.Rejected(Error.FOREIGN_HOST)
        if (authority.contains(':')) return Parsed.Rejected(Error.FOREIGN_HOST)
        // Kotlin's lowercase() takes no locale BECAUSE it folds with
        // Locale.ROOT (it compiles to String.toLowerCase(Locale.ROOT)); the
        // locale-sensitive fold is the separate lowercase(Locale) overload.
        // So a Turkish device cannot fold "INSTAGRAM" to "ınstagram" and miss
        // the allowlist. SocialLinksTest pins this under an actual tr-TR
        // default locale — do NOT "fix" this to lowercase(Locale.getDefault()).
        val host = authority.lowercase().removeSuffix(".")
        if (host !in spec.hosts) return Parsed.Rejected(Error.FOREIGN_HOST)

        val query = path.substringAfter('?', "").substringBefore('#')
        val segments =
            path.substringBefore('?')
                .substringBefore('#')
                .trim('/')
                .let { if (it.isEmpty()) emptyList() else it.split('/') }

        return when (platform) {
            SocialPlatform.FACEBOOK -> facebookHandle(spec, segments, query)
            SocialPlatform.INSTAGRAM -> instagramHandle(spec, segments)
            SocialPlatform.YOUTUBE -> youtubeHandle(spec, segments)
        }
    }

    private fun parseBare(spec: Spec, value: String): Parsed {
        // '@' is only ever the leading shorthand; anywhere else it is userinfo
        // or an e-mail address, neither of which is a handle.
        if (value.indexOf('@') > 0) return Parsed.Rejected(Error.MALFORMED)
        val bare = value.removePrefix("@")
        // "instagram.com" on its own names the SITE, not a member.
        if (bare.lowercase().removeSuffix(".") in spec.hosts) {
            return Parsed.Rejected(Error.MALFORMED)
        }
        return finish(spec, bare)
    }

    private fun facebookHandle(spec: Spec, segments: List<String>, query: String): Parsed {
        if (segments.isEmpty()) return Parsed.Rejected(Error.MALFORMED)
        // Accounts without a vanity username are linked as profile.php?id=NNN.
        // The numeric id also resolves at the root path, so it IS the handle.
        if (segments.size == 1 && segments[0].equals("profile.php", ignoreCase = true)) {
            val id = queryValue(query, "id") ?: return Parsed.Rejected(Error.UNSUPPORTED_LINK)
            return finish(spec, id)
        }
        // /groups/…, /events/…, /photo/… — real Facebook links, but not a person.
        if (segments.size != 1) return Parsed.Rejected(Error.UNSUPPORTED_LINK)
        return finish(spec, segments[0])
    }

    private fun instagramHandle(spec: Spec, segments: List<String>): Parsed {
        if (segments.isEmpty()) return Parsed.Rejected(Error.MALFORMED)
        // /p/…, /reel/…, /stories/… are posts, not profiles.
        if (segments.size != 1) return Parsed.Rejected(Error.UNSUPPORTED_LINK)
        return finish(spec, segments[0])
    }

    private fun youtubeHandle(spec: Spec, segments: List<String>): Parsed {
        if (segments.isEmpty()) return Parsed.Rejected(Error.MALFORMED)
        val first = segments[0]
        if (first.startsWith("@")) {
            if (segments.size != 1) return Parsed.Rejected(Error.UNSUPPORTED_LINK)
            return finish(spec, first.removePrefix("@"))
        }
        // A channel ID is NOT a handle: rendering UCxxxx as /@UCxxxx would be a
        // dead link, so it is refused loudly rather than silently mangled.
        if (first.equals("channel", ignoreCase = true)) {
            return Parsed.Rejected(Error.UNSUPPORTED_LINK)
        }
        // Legacy custom URLs — youtube.com/c/Name and youtube.com/user/Name —
        // carry the same name the handle was derived from.
        if (segments.size == 2 && (first.equals("c", true) || first.equals("user", true))) {
            return finish(spec, segments[1])
        }
        if (segments.size != 1) return Parsed.Rejected(Error.UNSUPPORTED_LINK)
        if (first.lowercase() in YOUTUBE_RESERVED_SEGMENTS) {
            return Parsed.Rejected(Error.UNSUPPORTED_LINK)
        }
        return finish(spec, first)
    }

    private fun finish(spec: Spec, candidate: String): Parsed {
        if (candidate.isEmpty()) return Parsed.Rejected(Error.MALFORMED)
        // lowercase() with no locale is locale-INVARIANT in Kotlin (Locale.ROOT),
        // so a Turkish device cannot turn "I" into "ı" and produce a stored
        // value the ASCII-only Security Rules pattern would then reject. The
        // ROOT fold is also the SAFER one here: under tr-TR, "sebİ" folds to
        // "sebi" — a different, real account — whereas ROOT folds it to
        // "sebi̇", which fails [pattern] and is refused. Proved in
        // SocialLinksTest under an actual tr-TR default locale.
        val normalised = if (spec.lowercase) candidate.lowercase() else candidate
        if (normalised.length > spec.maxLength) return Parsed.Rejected(Error.TOO_LONG)
        if (normalised.length < spec.minLength) return Parsed.Rejected(Error.MALFORMED)
        if (!spec.pattern.matches(normalised)) return Parsed.Rejected(Error.MALFORMED)
        return Parsed.Handle(normalised)
    }

    private fun queryValue(query: String, key: String): String? =
        query
            .split('&')
            .firstOrNull { it.substringBefore('=').equals(key, ignoreCase = true) }
            ?.substringAfter('=', "")
            ?.takeIf { it.isNotEmpty() }

    private fun Char.isRejectedChar(): Boolean =
        isWhitespace() ||
            code < 0x20 ||
            code == 0x7F ||
            code in 0x80..0x9F ||
            REJECTED_CHAR_TYPES.contains(Character.getType(this).toByte())

    /**
     * Character categories that never occur in a handle or in a pasted profile
     * URL. SPACE_SEPARATOR catches the non-breaking space (which
     * `Character.isWhitespace` does NOT report as whitespace), and FORMAT
     * catches the zero-width joiners and bidi overrides used to build
     * look-alike text.
     */
    private val REJECTED_CHAR_TYPES =
        setOf(
            Character.FORMAT,
            Character.CONTROL,
            Character.SPACE_SEPARATOR,
            Character.LINE_SEPARATOR,
            Character.PARAGRAPH_SEPARATOR,
            Character.SURROGATE,
            Character.PRIVATE_USE,
            Character.UNASSIGNED,
        )

    /**
     * Per-platform rules. [hosts] is the allowlist a pasted URL must match;
     * [pattern] is the shape of the STORED handle and is mirrored verbatim in
     * firebase/firestore.rules.
     */
    private data class Spec(
        val hosts: Set<String>,
        val pattern: Regex,
        val minLength: Int,
        val maxLength: Int,
        val lowercase: Boolean,
    )

    private fun specFor(platform: SocialPlatform): Spec =
        when (platform) {
            SocialPlatform.FACEBOOK -> FACEBOOK
            SocialPlatform.INSTAGRAM -> INSTAGRAM
            SocialPlatform.YOUTUBE -> YOUTUBE
        }

    // Facebook and Instagram usernames are case-insensitive and canonically
    // lowercase, so they are folded. YouTube handles are displayed with their
    // case, so theirs is preserved.
    private val FACEBOOK =
        Spec(
            hosts =
                setOf(
                    "facebook.com",
                    "www.facebook.com",
                    "m.facebook.com",
                    "web.facebook.com",
                    "mbasic.facebook.com",
                    "fb.com",
                    "www.fb.com",
                    "fb.me",
                ),
            pattern = Regex("^[a-z0-9][a-z0-9.-]{0,49}$"),
            minLength = 1,
            maxLength = 50,
            lowercase = true,
        )

    private val INSTAGRAM =
        Spec(
            hosts =
                setOf(
                    "instagram.com",
                    "www.instagram.com",
                    "m.instagram.com",
                    "instagr.am",
                    "www.instagr.am",
                ),
            pattern = Regex("^[a-z0-9_][a-z0-9._]{0,29}$"),
            minLength = 1,
            maxLength = 30,
            lowercase = true,
        )

    private val YOUTUBE =
        Spec(
            // youtu.be is deliberately ABSENT: it is a video permalink host, not
            // a channel one, so accepting it would turn a video link into a
            // confident-looking but bogus channel handle.
            hosts =
                setOf(
                    "youtube.com",
                    "www.youtube.com",
                    "m.youtube.com",
                    "music.youtube.com",
                ),
            pattern = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{2,29}$"),
            minLength = 3,
            maxLength = 30,
            lowercase = false,
        )

    /** Single-segment YouTube paths that are features of the site, not channels. */
    private val YOUTUBE_RESERVED_SEGMENTS =
        setOf(
            "watch",
            "playlist",
            "shorts",
            "results",
            "feed",
            "embed",
            "live",
            "hashtag",
            "about",
            "channel",
        )
}
