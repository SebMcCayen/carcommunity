package com.kungsbackacarcommunity.app.feedback

/**
 * "Report a problem" form + validation (Android → backend feedback.reportIssue).
 *
 * Mirrors the backend schema: `description` is required; `summary` is an
 * optional short title. `appVersion`/`osVersion`/`deviceModel` are collected
 * automatically at the call site and are never typed by the user.
 *
 * PUBLIC-REPO SAFETY: the report is filed as a world-readable GitHub issue.
 * The user is warned (feedback_publicNotice) not to type PII; the backend
 * additionally guarantees no uid/PII enters the public issue body. Pure Kotlin
 * — JVM-testable with no Firebase or Android imports.
 */
data class FeedbackReportForm(
    val summary: String = "",
    val description: String = "",
)

/** Auto-collected client context (never typed by the user). */
data class FeedbackClientContext(
    val appVersion: String?,
    val osVersion: String?,
    val deviceModel: String?,
)

data class FeedbackReportInput(
    val summary: String?,
    val description: String,
    val appVersion: String?,
    val osVersion: String?,
    val deviceModel: String?,
)

/** First invalid field, or null when the form is submittable. */
enum class FeedbackReportError {
    DESCRIPTION_REQUIRED,
}

object FeedbackReports {
    /** Backend caps (contract MAX_SUMMARY_LENGTH / MAX_DESCRIPTION_LENGTH). */
    const val MAX_SUMMARY_LENGTH = 80
    const val MAX_DESCRIPTION_LENGTH = 4000

    /** Null when submittable; otherwise the first invalid field. */
    fun validate(form: FeedbackReportForm): FeedbackReportError? {
        if (form.description.trim().isEmpty()) return FeedbackReportError.DESCRIPTION_REQUIRED
        return null
    }

    /**
     * Builds the callable input from the form + auto-collected context, or null
     * when the form is invalid. Trims and bounds text to the backend caps.
     */
    fun toInput(form: FeedbackReportForm, context: FeedbackClientContext): FeedbackReportInput? {
        if (validate(form) != null) return null
        return FeedbackReportInput(
            summary = form.summary.trim().takeIf { it.isNotEmpty() }?.take(MAX_SUMMARY_LENGTH),
            description = form.description.trim().take(MAX_DESCRIPTION_LENGTH),
            appVersion = context.appVersion?.takeIf { it.isNotBlank() },
            osVersion = context.osVersion?.takeIf { it.isNotBlank() },
            deviceModel = context.deviceModel?.takeIf { it.isNotBlank() },
        )
    }
}
