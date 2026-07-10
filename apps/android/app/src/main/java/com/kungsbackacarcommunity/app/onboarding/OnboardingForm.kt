package com.kungsbackacarcommunity.app.onboarding

/**
 * Pure onboarding-form logic (Phase 12 slice 2). No Android/Firebase types
 * so it is JVM-unit-testable (the `./gradlew test` gate).
 *
 * Mirrors the auth.completeOnboarding contract
 * (contracts/schemas/auth.schema.json / functions onboarding-core): the
 * three consents are all mandatory, and the display name is REQUIRED and must
 * be 1..120 characters after trimming. The display name is the user's public
 * profile name — it is never derived from the Google account name.
 */
object OnboardingForm {

    const val DISPLAY_NAME_MAX_LENGTH = 120

    /**
     * All three legally-required consents must be checked AND a non-blank,
     * valid-length display name must be entered before submission is allowed.
     */
    fun canSubmit(
        ageConfirmed: Boolean,
        termsAccepted: Boolean,
        privacyAccepted: Boolean,
        displayName: String,
    ): Boolean =
        ageConfirmed && termsAccepted && privacyAccepted && isDisplayNameValid(displayName)

    /** True when the trimmed display name is non-blank and within the max length. */
    fun isDisplayNameValid(raw: String): Boolean {
        val trimmed = raw.trim()
        return trimmed.isNotEmpty() && trimmed.length <= DISPLAY_NAME_MAX_LENGTH
    }

    /**
     * Normalizes the required display name for the callable: trimmed, or null
     * when invalid (blank or over-long). Callers gate submission on
     * [canSubmit], so a valid form always yields a non-null value.
     */
    fun normalizedDisplayName(raw: String): String? =
        raw.trim().takeIf { it.isNotEmpty() && it.length <= DISPLAY_NAME_MAX_LENGTH }

    /** True when a non-empty display name exceeds the max length. */
    fun isDisplayNameTooLong(raw: String): Boolean = raw.trim().length > DISPLAY_NAME_MAX_LENGTH
}
