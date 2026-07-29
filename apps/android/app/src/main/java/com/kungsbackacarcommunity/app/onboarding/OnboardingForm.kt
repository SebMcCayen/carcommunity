package com.kungsbackacarcommunity.app.onboarding

/**
 * Pure onboarding-form logic (Phase 12 slice 2). No Android/Firebase types
 * so it is JVM-unit-testable (the `./gradlew test` gate).
 *
 * The three consents are all mandatory, mirroring the auth.completeOnboarding
 * contract (contracts/schemas/auth.schema.json / functions onboarding-core),
 * which requires all three to be literally true.
 *
 * The display name is CLIENT-ENFORCED as required here: this form does not
 * enable submission until a non-blank display name of 1..120 characters (after
 * trimming) is entered, and it is never prefilled from the Google account name.
 * This is intentionally stricter than the current backend contract — server
 * side `displayName` is still OPTIONAL (functions onboarding-core), and when a
 * client omits it completeOnboarding seeds it from `auth.token.name` during
 * provisioning (functions completeOnboarding.ts). The stricter client rule
 * ensures members always choose their own public profile name.
 */
object OnboardingForm {

    const val DISPLAY_NAME_MAX_LENGTH = 120

    /**
     * All three legally-required consents must be checked AND a non-blank,
     * valid-length display name must be entered before submission is allowed.
     */
    fun canSubmit(
        licenceConfirmed: Boolean,
        termsAccepted: Boolean,
        privacyAccepted: Boolean,
        displayName: String,
    ): Boolean =
        licenceConfirmed && termsAccepted && privacyAccepted && isDisplayNameValid(displayName)

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
