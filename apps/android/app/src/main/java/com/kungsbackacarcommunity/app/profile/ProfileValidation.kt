package com.kungsbackacarcommunity.app.profile

/**
 * Pure profile-edit validation (Phase 12 slice 2), mirroring the Phase 9a
 * owner-write rules for users/{uid} (displayName 1..120, bio 0..500). No
 * Android/Firebase types so it is JVM-unit-testable.
 */
object ProfileValidation {
    const val DISPLAY_NAME_MAX = 120
    const val BIO_MAX = 500

    enum class FieldError { REQUIRED, TOO_LONG }

    data class Result(
        val displayNameError: FieldError?,
        val bioError: FieldError?,
    ) {
        val isValid: Boolean get() = displayNameError == null && bioError == null
    }

    fun validate(displayName: String, bio: String): Result {
        val name = displayName.trim()
        val nameError =
            when {
                name.isEmpty() -> FieldError.REQUIRED
                name.length > DISPLAY_NAME_MAX -> FieldError.TOO_LONG
                else -> null
            }
        val bioError = if (bio.trim().length > BIO_MAX) FieldError.TOO_LONG else null
        return Result(nameError, bioError)
    }
}
