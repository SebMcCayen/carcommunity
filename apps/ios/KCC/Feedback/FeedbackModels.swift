import Foundation

struct FeedbackReportForm: Equatable, Sendable {
    var summary = ""
    var description = ""
}

struct FeedbackClientContext: Equatable, Sendable {
    let appVersion: String?
    let osVersion: String?
    let deviceModel: String?

    static func sanitized(
        appVersion: String?,
        osVersion: String?,
        deviceModel: String?
    ) -> FeedbackClientContext {
        FeedbackClientContext(
            appVersion: FeedbackText.boundContext(appVersion, limit: 50),
            osVersion: FeedbackText.boundContext(osVersion, limit: 100),
            deviceModel: FeedbackText.boundContext(deviceModel, limit: 100)
        )
    }
}

struct FeedbackReportInput: Equatable, Sendable {
    let summary: String?
    let description: String
    let appVersion: String?
    let osVersion: String?
    let deviceModel: String?
}

enum FeedbackFormError: Equatable, Sendable {
    case descriptionRequired
}

enum FeedbackReports {
    static let maximumSummaryLength = 80
    static let maximumDescriptionLength = 4_000

    static func validate(_ form: FeedbackReportForm) -> FeedbackFormError? {
        FeedbackText.boundMultiline(form.description, limit: maximumDescriptionLength).isEmpty
            ? .descriptionRequired : nil
    }

    static func input(
        from form: FeedbackReportForm,
        context: FeedbackClientContext
    ) -> FeedbackReportInput? {
        guard validate(form) == nil else { return nil }
        let summary = FeedbackText.boundMultiline(form.summary, limit: maximumSummaryLength)
        return FeedbackReportInput(
            summary: summary.isEmpty ? nil : summary,
            description: FeedbackText.boundMultiline(
                form.description,
                limit: maximumDescriptionLength
            ),
            appVersion: context.appVersion,
            osVersion: context.osVersion,
            deviceModel: context.deviceModel
        )
    }
}

enum FeedbackFailure: Equatable, Sendable {
    case rateLimited
    case signedOut
    case unavailable
    case unknown
}

struct FeedbackSubmitResult: Equatable, Sendable {
    let reportId: String
    let issueURL: URL?
    let issueNumber: Int?
}

enum FeedbackStatus: Equatable, Sendable {
    case idle
    case unavailable
    case submitting
    case submitted(issueURL: URL?, issueNumber: Int?, summary: String?)
    case failed(FeedbackFailure)
}

enum FeedbackText {
    private static let multilineControls = CharacterSet.controlCharacters
        .subtracting(CharacterSet(charactersIn: "\t\n"))

    static func boundMultiline(_ value: String, limit: Int) -> String {
        let cleaned = value.unicodeScalars
            .filter { !multilineControls.contains($0) }
            .map(String.init)
            .joined()
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return prefixByUTF16(cleaned, limit: limit)
    }

    static func boundContext(_ value: String?, limit: Int) -> String? {
        guard let value else { return nil }
        let cleaned = value.unicodeScalars
            .map { CharacterSet.controlCharacters.contains($0) ? " " : String($0) }
            .joined()
            .split(whereSeparator: \.isWhitespace)
            .joined(separator: " ")
        let bounded = prefixByUTF16(cleaned, limit: limit)
        return bounded.isEmpty ? nil : bounded
    }

    /// JavaScript/Zod bounds strings in UTF-16 code units. Match that limit
    /// without splitting a scalar so emoji-heavy input cannot be rejected by
    /// the backend after passing client validation.
    static func prefixByUTF16(_ value: String, limit: Int) -> String {
        guard limit > 0 else { return "" }
        var result = ""
        var used = 0
        for scalar in value.unicodeScalars {
            let width = scalar.value > 0xFFFF ? 2 : 1
            guard used + width <= limit else { break }
            result.unicodeScalars.append(scalar)
            used += width
        }
        return result
    }
}

enum GitHubIssueLinks {
    /// Only normal web links on GitHub may leave the app. Credentials and
    /// explicit ports are rejected so a compromised mirror cannot disguise a
    /// different authority or invoke a non-web URL scheme.
    static func safeURL(_ rawValue: String?) -> URL? {
        guard let rawValue,
              let components = URLComponents(string: rawValue),
              let scheme = components.scheme?.lowercased(),
              scheme == "https",
              components.user == nil,
              components.password == nil,
              components.port == nil,
              let host = components.host?.lowercased(),
              host == "github.com",
              components.path.range(
                of: #"^/SebMcCayen/carcommunity/issues/[1-9][0-9]*$"#,
                options: [.regularExpression, .caseInsensitive]
              ) != nil,
              let url = components.url
        else { return nil }
        return url
    }
}
