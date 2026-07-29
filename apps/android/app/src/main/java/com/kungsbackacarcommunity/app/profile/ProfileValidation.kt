package com.kungsbackacarcommunity.app.profile

/**
 * Pure profile-edit validation (Phase 12 slice 2), mirroring the Phase 9a
 * owner-write rules for users/{uid} (displayName 1..120, bio 0..500) and the
 * social-handle rules added alongside them. No Android/Firebase types so it is
 * JVM-unit-testable.
 *
 * The social fields are validated by [SocialLinks], which also NORMALISES what
 * the member typed — [Result.social] carries the canonical handles the save
 * must write, so the caller never re-derives them from the raw text.
 */
object ProfileValidation {
    const val DISPLAY_NAME_MAX = 120
    const val BIO_MAX = 500

    enum class FieldError { REQUIRED, TOO_LONG }

    data class Result(
        val displayNameError: FieldError?,
        val bioError: FieldError?,
        val facebookError: SocialLinks.Error? = null,
        val instagramError: SocialLinks.Error? = null,
        val youtubeError: SocialLinks.Error? = null,
        /**
         * The canonical handles the save must write. A field the member left
         * blank is null here — that is a CLEAR, not a skip
         * (FirebaseProfileRepository deletes the field). A field that failed
         * validation is also null, but [isValid] is false so no save runs.
         */
        val social: SocialHandles = SocialHandles.EMPTY,
    ) {
        val isValid: Boolean
            get() =
                displayNameError == null &&
                    bioError == null &&
                    facebookError == null &&
                    instagramError == null &&
                    youtubeError == null
    }

    fun validate(
        displayName: String,
        bio: String,
        facebook: String = "",
        instagram: String = "",
        youtube: String = "",
    ): Result {
        val name = displayName.trim()
        val nameError =
            when {
                name.isEmpty() -> FieldError.REQUIRED
                name.length > DISPLAY_NAME_MAX -> FieldError.TOO_LONG
                else -> null
            }
        val bioError = if (bio.trim().length > BIO_MAX) FieldError.TOO_LONG else null

        val fb = SocialLinks.parse(SocialPlatform.FACEBOOK, facebook)
        val ig = SocialLinks.parse(SocialPlatform.INSTAGRAM, instagram)
        val yt = SocialLinks.parse(SocialPlatform.YOUTUBE, youtube)

        return Result(
            displayNameError = nameError,
            bioError = bioError,
            facebookError = fb.errorOrNull(),
            instagramError = ig.errorOrNull(),
            youtubeError = yt.errorOrNull(),
            social =
                SocialHandles(
                    facebook = fb.handleOrNull(),
                    instagram = ig.handleOrNull(),
                    youtube = yt.handleOrNull(),
                ),
        )
    }

    private fun SocialLinks.Parsed.errorOrNull(): SocialLinks.Error? =
        (this as? SocialLinks.Parsed.Rejected)?.error

    private fun SocialLinks.Parsed.handleOrNull(): String? =
        (this as? SocialLinks.Parsed.Handle)?.value
}
