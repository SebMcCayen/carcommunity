package com.kungsbackacarcommunity.app.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The security of the social-links feature lives in [SocialLinks], so this is
 * where it is proved: what a member types is normalised to one canonical
 * handle, and everything that could turn a profile link into a phishing hop is
 * refused rather than repaired.
 *
 * The SERVER-side half of the same contract (the identical patterns, enforced
 * by Firestore itself on the direct owner write) is proved in
 * functions/src/__tests__/security-rules.emulator.test.ts — this file cannot
 * stand in for it, because a hostile client never runs this code.
 */
class SocialLinksTest {

    private fun handle(platform: SocialPlatform, raw: String): String? =
        (SocialLinks.parse(platform, raw) as? SocialLinks.Parsed.Handle)?.value

    private fun error(platform: SocialPlatform, raw: String): SocialLinks.Error? =
        (SocialLinks.parse(platform, raw) as? SocialLinks.Parsed.Rejected)?.error

    private fun assertRejected(
        platform: SocialPlatform,
        raw: String,
        expected: SocialLinks.Error,
    ) {
        assertEquals("input=$raw", expected, error(platform, raw))
    }

    // ---- normalisation: every shape a member might type --------------------

    @Test
    fun `instagram accepts a bare handle, an at-handle, a bare domain and a full url`() {
        val shapes =
            listOf(
                "sebmccayen",
                "@sebmccayen",
                "  sebmccayen  ",
                "instagram.com/sebmccayen",
                "www.instagram.com/sebmccayen",
                "m.instagram.com/sebmccayen",
                "instagr.am/sebmccayen",
                "//instagram.com/sebmccayen",
                "http://instagram.com/sebmccayen",
                "https://instagram.com/sebmccayen",
                "https://www.instagram.com/sebmccayen/",
                "https://www.instagram.com/sebmccayen?igshid=Zm5hbGw",
                "https://www.instagram.com/sebmccayen/?igshid=Zm5hbGw",
                "https://www.instagram.com/sebmccayen#about",
                "https://WWW.INSTAGRAM.COM/SebMcCayen/",
                "SEBMCCAYEN",
            )
        shapes.forEach { shape ->
            assertEquals("input=$shape", "sebmccayen", handle(SocialPlatform.INSTAGRAM, shape))
        }
    }

    @Test
    fun `facebook accepts its host aliases, dots and hyphens, and profile-id links`() {
        assertEquals("sebmccayen", handle(SocialPlatform.FACEBOOK, "SebMcCayen"))
        assertEquals("seb.mccayen", handle(SocialPlatform.FACEBOOK, "https://fb.com/Seb.McCayen"))
        assertEquals(
            "kungsbacka-car-community-123456",
            handle(SocialPlatform.FACEBOOK, "https://m.facebook.com/Kungsbacka-Car-Community-123456"),
        )
        // No vanity username: Facebook's own link is profile.php?id=NNN, and the
        // numeric id resolves at the root path — so it IS the handle.
        assertEquals(
            "100001234567890",
            handle(
                SocialPlatform.FACEBOOK,
                "https://www.facebook.com/profile.php?id=100001234567890",
            ),
        )
    }

    @Test
    fun `youtube accepts at-handles and the legacy custom-url paths, preserving case`() {
        listOf(
            "SebMcCayen",
            "@SebMcCayen",
            "youtube.com/@SebMcCayen",
            "https://www.youtube.com/@SebMcCayen",
            "https://www.youtube.com/@SebMcCayen/",
            "https://m.youtube.com/@SebMcCayen?si=abc",
            "https://www.youtube.com/c/SebMcCayen",
            "https://www.youtube.com/user/SebMcCayen",
        ).forEach { shape ->
            assertEquals("input=$shape", "SebMcCayen", handle(SocialPlatform.YOUTUBE, shape))
        }
    }

    @Test
    fun `facebook and instagram fold case but youtube preserves it`() {
        assertEquals("sebmccayen", handle(SocialPlatform.FACEBOOK, "SebMcCayen"))
        assertEquals("sebmccayen", handle(SocialPlatform.INSTAGRAM, "SebMcCayen"))
        assertEquals("SebMcCayen", handle(SocialPlatform.YOUTUBE, "SebMcCayen"))
    }

    // ---- clearing ----------------------------------------------------------

    @Test
    fun `an empty or blank value clears the field rather than storing junk`() {
        SocialPlatform.entries.forEach { platform ->
            listOf("", "   ", "\t", "\n").forEach { blank ->
                assertEquals(
                    "platform=$platform blank=${blank.trim().length}",
                    SocialLinks.Parsed.Empty,
                    SocialLinks.parse(platform, blank),
                )
            }
        }
    }

    @Test
    fun `a cleared field is null in the validation result, never an empty string`() {
        val result = ProfileValidation.validate("Sebbe", "bio", facebook = "", instagram = "  ")
        assertTrue(result.isValid)
        assertNull(result.social.facebook)
        assertNull(result.social.instagram)
        assertNull(result.social.youtube)
        assertEquals(SocialHandles.EMPTY, result.social)
    }

    // ---- foreign hosts: the phishing case ----------------------------------

    @Test
    fun `a foreign host is refused for every platform`() {
        SocialPlatform.entries.forEach { platform ->
            listOf(
                "https://evil.com/x",
                "http://evil.com/sebmccayen",
                "//evil.com/x",
                "evil.com/x",
                "https://evil.com",
            ).forEach { input ->
                assertRejected(platform, input, SocialLinks.Error.FOREIGN_HOST)
            }
        }
    }

    @Test
    fun `a look-alike host is not the real one`() {
        listOf(
            "https://instagram.com.evil.com/sebmccayen",
            "https://evil-instagram.com/sebmccayen",
            "https://instagram.evil.com/sebmccayen",
            "https://xn--instagram-x59d.com/sebmccayen",
        ).forEach { input ->
            assertRejected(SocialPlatform.INSTAGRAM, input, SocialLinks.Error.FOREIGN_HOST)
        }
    }

    @Test
    fun `one platform's host is a foreign host to another`() {
        assertRejected(
            SocialPlatform.INSTAGRAM,
            "https://www.facebook.com/sebmccayen",
            SocialLinks.Error.FOREIGN_HOST,
        )
        // youtu.be is a VIDEO permalink host, deliberately not a channel host:
        // accepting it would turn a video link into a bogus channel handle.
        assertRejected(
            SocialPlatform.YOUTUBE,
            "https://youtu.be/dQw4w9WgXcQ",
            SocialLinks.Error.FOREIGN_HOST,
        )
    }

    @Test
    fun `userinfo cannot smuggle a foreign host past the allowlist`() {
        listOf(
            "https://evil.com@www.instagram.com/sebmccayen",
            "https://www.instagram.com@evil.com/sebmccayen",
            "https://user:pass@instagram.com/sebmccayen",
            "https://instagram.com:pass@evil.com/x",
        ).forEach { input ->
            assertRejected(SocialPlatform.INSTAGRAM, input, SocialLinks.Error.FOREIGN_HOST)
        }
    }

    @Test
    fun `a non-standard port is refused`() {
        assertRejected(
            SocialPlatform.INSTAGRAM,
            "https://instagram.com:8080/sebmccayen",
            SocialLinks.Error.FOREIGN_HOST,
        )
        assertRejected(
            SocialPlatform.INSTAGRAM,
            "https://instagram.com:443/sebmccayen",
            SocialLinks.Error.FOREIGN_HOST,
        )
    }

    // ---- schemes -----------------------------------------------------------

    @Test
    fun `non-web schemes are refused, with and without a double slash`() {
        listOf(
            "javascript:alert(1)",
            "javascript://instagram.com/%0aalert(1)",
            "data:text/html,<script>alert(1)</script>",
            "data://instagram.com/x",
            "file:///etc/passwd",
            "ftp://instagram.com/sebmccayen",
            "intent://instagram.com/x#Intent;scheme=https;end",
            "mailto:seb@example.com",
            "content://com.evil/x",
            "instagram.com:8080",
        ).forEach { input ->
            assertRejected(SocialPlatform.INSTAGRAM, input, SocialLinks.Error.MALFORMED)
        }
    }

    // ---- character hygiene -------------------------------------------------

    @Test
    fun `control characters, whitespace and zero-width characters are refused`() {
        // Written as escapes on purpose: every one of these is invisible or
        // near-invisible in a diff, which is exactly why they are the vector.
        listOf(
            "seb mccayen", // plain space
            "seb\tmccayen", // tab
            "seb\nmccayen", // newline — the classic header/URL injection
            "seb\rmccayen", // carriage return
            "seb\u0000mccayen", // NUL
            "seb\u0007mccayen", // BEL
            "seb\u007Fmccayen", // DEL
            "seb\u0085mccayen", // C1 next-line
            "seb\u00A0mccayen", // non-breaking space (NOT Char.isWhitespace)
            "seb\u200Bmccayen", // zero-width space
            "seb\u200Dmccayen", // zero-width joiner
            "seb\u200Emccayen", // left-to-right mark
            "seb\u202Emccayen", // right-to-left override
            "seb\u2028mccayen", // line separator
            "seb\uFEFFmccayen", // byte-order mark
            "https://www.instagram.com/seb\nmccayen",
            "https://www.instagram.com/seb\u200Bmccayen",
        ).forEach { input ->
            assertRejected(SocialPlatform.INSTAGRAM, input, SocialLinks.Error.MALFORMED)
        }
    }

    @Test
    fun `non-ascii look-alike characters are refused`() {
        listOf(
            "s\u0435bmccayen", // Cyrillic small letter IE
            "s\uFF45bmccayen", // full-width latin small letter E
            "sebmcc\u0430yen", // Cyrillic small letter A
            "\u0131nstagram", // dotless i
        ).forEach { input ->
            assertRejected(SocialPlatform.INSTAGRAM, input, SocialLinks.Error.MALFORMED)
        }
    }

    // ---- length ------------------------------------------------------------

    @Test
    fun `an over-long handle is refused at the platform bound`() {
        assertEquals("a".repeat(30), handle(SocialPlatform.INSTAGRAM, "a".repeat(30)))
        assertRejected(SocialPlatform.INSTAGRAM, "a".repeat(31), SocialLinks.Error.TOO_LONG)
        assertEquals("a".repeat(50), handle(SocialPlatform.FACEBOOK, "a".repeat(50)))
        assertRejected(SocialPlatform.FACEBOOK, "a".repeat(51), SocialLinks.Error.TOO_LONG)
        assertEquals("a".repeat(30), handle(SocialPlatform.YOUTUBE, "a".repeat(30)))
        assertRejected(SocialPlatform.YOUTUBE, "a".repeat(31), SocialLinks.Error.TOO_LONG)
    }

    @Test
    fun `an over-long input is refused before it is parsed`() {
        val input = "https://www.instagram.com/" + "a".repeat(SocialLinks.MAX_INPUT_LENGTH)
        assertRejected(SocialPlatform.INSTAGRAM, input, SocialLinks.Error.TOO_LONG)
    }

    @Test
    fun `a too-short youtube handle is refused`() {
        assertRejected(SocialPlatform.YOUTUBE, "ab", SocialLinks.Error.MALFORMED)
        assertEquals("abc", handle(SocialPlatform.YOUTUBE, "abc"))
    }

    // ---- link shapes that are the right host but not a profile -------------

    @Test
    fun `the bare site name is not a member`() {
        assertRejected(SocialPlatform.INSTAGRAM, "instagram.com", SocialLinks.Error.MALFORMED)
        assertRejected(SocialPlatform.INSTAGRAM, "https://instagram.com", SocialLinks.Error.MALFORMED)
        assertRejected(SocialPlatform.INSTAGRAM, "https://instagram.com/", SocialLinks.Error.MALFORMED)
        assertRejected(SocialPlatform.FACEBOOK, "www.facebook.com", SocialLinks.Error.MALFORMED)
    }

    @Test
    fun `posts, groups and videos are refused instead of being read as handles`() {
        assertRejected(
            SocialPlatform.INSTAGRAM,
            "https://www.instagram.com/p/Cabcdef123/",
            SocialLinks.Error.UNSUPPORTED_LINK,
        )
        assertRejected(
            SocialPlatform.INSTAGRAM,
            "https://www.instagram.com/stories/sebmccayen/123/",
            SocialLinks.Error.UNSUPPORTED_LINK,
        )
        assertRejected(
            SocialPlatform.FACEBOOK,
            "https://www.facebook.com/groups/kungsbacka",
            SocialLinks.Error.UNSUPPORTED_LINK,
        )
        assertRejected(
            SocialPlatform.YOUTUBE,
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            SocialLinks.Error.UNSUPPORTED_LINK,
        )
        assertRejected(
            SocialPlatform.YOUTUBE,
            "https://www.youtube.com/playlist?list=PLabc",
            SocialLinks.Error.UNSUPPORTED_LINK,
        )
    }

    @Test
    fun `a youtube channel-id link is refused rather than mangled into a handle`() {
        // /@UCabc… would be a confident-looking dead link, so it is refused and
        // the member is asked for their handle instead.
        assertRejected(
            SocialPlatform.YOUTUBE,
            "https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv",
            SocialLinks.Error.UNSUPPORTED_LINK,
        )
    }

    @Test
    fun `an at sign anywhere but the front is not a handle`() {
        assertRejected(SocialPlatform.INSTAGRAM, "seb@evil.com", SocialLinks.Error.MALFORMED)
        assertRejected(SocialPlatform.INSTAGRAM, "seb@@mccayen", SocialLinks.Error.MALFORMED)
    }

    @Test
    fun `path traversal cannot be smuggled through a handle`() {
        assertRejected(
            SocialPlatform.INSTAGRAM,
            "https://www.instagram.com/../../evil",
            SocialLinks.Error.UNSUPPORTED_LINK,
        )
        assertRejected(SocialPlatform.INSTAGRAM, "..", SocialLinks.Error.MALFORMED)
    }

    // ---- canonical URL: the host is ours, always ---------------------------

    @Test
    fun `a canonical url is built from a constant host`() {
        assertEquals(
            "https://www.facebook.com/sebmccayen",
            SocialLinks.canonicalUrl(SocialPlatform.FACEBOOK, "sebmccayen"),
        )
        assertEquals(
            "https://www.instagram.com/sebmccayen",
            SocialLinks.canonicalUrl(SocialPlatform.INSTAGRAM, "sebmccayen"),
        )
        assertEquals(
            "https://www.youtube.com/@SebMcCayen",
            SocialLinks.canonicalUrl(SocialPlatform.YOUTUBE, "SebMcCayen"),
        )
    }

    @Test
    fun `a stored value that is not exactly canonical produces no url at all`() {
        // Defence in depth: a document written before the Security Rules landed,
        // or by any future Admin SDK path, must not be able to render a link.
        listOf(
            "evil.com/x",
            "../../evil",
            "seb@evil.com",
            "SebMcCayen",
            "seb mccayen",
            "seb ",
            "",
            "a".repeat(31),
            ".sebmccayen",
        ).forEach { stored ->
            assertNull("stored=$stored", SocialLinks.canonicalUrl(SocialPlatform.INSTAGRAM, stored))
        }
    }

    // ---- what the profile surfaces render ----------------------------------

    @Test
    fun `nothing renders when the member has filled nothing in`() {
        assertEquals(emptyList<SocialLink>(), SocialLinks.links(SocialHandles.EMPTY))
        assertEquals(
            emptyList<SocialLink>(),
            SocialLinks.links(SocialHandles(facebook = null, instagram = null, youtube = null)),
        )
        // An empty string is not a link either — it is what an older document
        // might carry, and it must render as "unset", not as an empty icon.
        assertEquals(
            emptyList<SocialLink>(),
            SocialLinks.links(SocialHandles(facebook = "", instagram = "", youtube = "")),
        )
    }

    @Test
    fun `a corrupt stored handle renders nothing while its siblings still render`() {
        val links =
            SocialLinks.links(
                SocialHandles(
                    facebook = "https://evil.com/x",
                    instagram = "sebmccayen",
                    youtube = "SebMcCayen",
                ),
            )
        assertEquals(
            listOf(SocialPlatform.INSTAGRAM, SocialPlatform.YOUTUBE),
            links.map { it.platform },
        )
    }

    @Test
    fun `filled links come back in a fixed platform order with their urls`() {
        val links =
            SocialLinks.links(
                SocialHandles(
                    facebook = "sebmccayen",
                    instagram = "sebmccayen",
                    youtube = "SebMcCayen",
                ),
            )
        assertEquals(
            listOf(
                "https://www.facebook.com/sebmccayen",
                "https://www.instagram.com/sebmccayen",
                "https://www.youtube.com/@SebMcCayen",
            ),
            links.map { it.url },
        )
    }

    // ---- the edit form's view of all this ----------------------------------

    @Test
    fun `validation surfaces the social error and refuses to save`() {
        val result =
            ProfileValidation.validate(
                displayName = "Sebbe",
                bio = "",
                instagram = "https://evil.com/sebmccayen",
            )
        assertEquals(SocialLinks.Error.FOREIGN_HOST, result.instagramError)
        assertEquals(false, result.isValid)
        assertNull(result.social.instagram)
    }

    @Test
    fun `validation hands the caller canonical handles, not what was typed`() {
        val result =
            ProfileValidation.validate(
                displayName = "Sebbe",
                bio = "",
                facebook = "https://www.facebook.com/profile.php?id=100001234567890",
                instagram = "https://www.instagram.com/SebMcCayen/?igshid=abc",
                youtube = "@SebMcCayen",
            )
        assertTrue(result.isValid)
        assertEquals(
            SocialHandles(
                facebook = "100001234567890",
                instagram = "sebmccayen",
                youtube = "SebMcCayen",
            ),
            result.social,
        )
    }
}
