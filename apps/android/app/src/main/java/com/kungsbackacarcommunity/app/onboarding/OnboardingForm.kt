package com.kungsbackacarcommunity.app.onboarding

/**
 * Pure onboarding-form logic (Phase 12 slice 2). No Android/Firebase types
 * so it is JVM-unit-testable (the `./gradlew test` gate).
 *
 * Mirrors the auth.completeOnboarding contract
 * (contracts/schemas/auth.schema.json / functions onboarding-core): the
 * three consents are all mandatory; the display name is optional and, if
 * present, must be 1..120 characters after trimming.
 */
object OnboardingForm {

    const val DISPLAY_NAME_MAX_LENGTH = 120

    /** All three legally-required consents must be checked to submit. */
    fun canSubmit(
        ageConfirmed: Boolean,
        termsAccepted: Boolean,
        privacyAccepted: Boolean,
    ): Boolean = ageConfirmed && termsAccepted && privacyAccepted

    /**
     * Normalizes the optional display name for the callable: trimmed, or
     * null when blank (the backend schema rejects empty strings, so a blank
     * field must be omitted rather than sent).
     */
    fun normalizedDisplayName(raw: String): String? =
        raw.trim().takeIf { it.isNotEmpty() && it.length <= DISPLAY_NAME_MAX_LENGTH }

    /** True when a non-empty display name exceeds the max length. */
    fun isDisplayNameTooLong(raw: String): Boolean = raw.trim().length > DISPLAY_NAME_MAX_LENGTH
}
