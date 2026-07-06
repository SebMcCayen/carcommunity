package com.kungsbackacarcommunity.app.partners

/**
 * Partner-application form + validation (Phase 12 slice 18).
 *
 * Mirrors the backend submitApplication schema: companyName + contactName +
 * a valid contactEmail + a category are required; phone/website/message are
 * optional. Contact data is never client-readable after submission. Pure
 * Kotlin — JVM-testable (a lightweight email check; the backend validates
 * strictly).
 */
data class PartnerApplicationForm(
    val companyName: String = "",
    val category: PartnerCategory? = null,
    val contactName: String = "",
    val contactEmail: String = "",
    val contactPhone: String = "",
    val websiteUrl: String = "",
    val message: String = "",
)

data class PartnerApplicationInput(
    val companyName: String,
    val category: PartnerCategory,
    val contactName: String,
    val contactEmail: String,
    val contactPhone: String?,
    val websiteUrl: String?,
    val message: String?,
)

/** First invalid field, or null when the form is submittable. */
enum class PartnerApplicationError {
    COMPANY_NAME_REQUIRED,
    CATEGORY_REQUIRED,
    CONTACT_NAME_REQUIRED,
    CONTACT_EMAIL_INVALID,
}

object PartnerApplications {
    private fun looksLikeEmail(value: String): Boolean {
        val at = value.indexOf('@')
        return at > 0 && value.indexOf('@', at + 1) == -1 && value.substring(at + 1).contains('.') &&
            !value.endsWith(".") && !value.contains(' ')
    }

    fun validate(form: PartnerApplicationForm): PartnerApplicationError? {
        if (form.companyName.trim().isEmpty()) return PartnerApplicationError.COMPANY_NAME_REQUIRED
        if (form.category == null) return PartnerApplicationError.CATEGORY_REQUIRED
        if (form.contactName.trim().isEmpty()) return PartnerApplicationError.CONTACT_NAME_REQUIRED
        if (!looksLikeEmail(form.contactEmail.trim())) return PartnerApplicationError.CONTACT_EMAIL_INVALID
        return null
    }

    fun toInput(form: PartnerApplicationForm): PartnerApplicationInput? {
        if (validate(form) != null) return null
        return PartnerApplicationInput(
            companyName = form.companyName.trim(),
            category = form.category!!,
            contactName = form.contactName.trim(),
            contactEmail = form.contactEmail.trim(),
            contactPhone = form.contactPhone.trim().takeIf { it.isNotEmpty() },
            websiteUrl = form.websiteUrl.trim().takeIf { it.isNotEmpty() },
            message = form.message.trim().takeIf { it.isNotEmpty() },
        )
    }
}
