import Foundation

/// Chat-hub domain: the four hub tabs, the caller's chat-eligible convoy rows,
/// and the pure projection/grouping/formatting behind the Convoys tab — the iOS
/// port of the hub pieces of Android's `chatchannels/` (`ChatTab` in
/// `ChatHubScreen.kt`, `ChatConvoy`/`ConvoyListState`/`ConvoyChatMapper` in
/// `ConvoyChatRepository.kt`, and `ConvoyRowFormat.kt`). Pure Swift, so every
/// rule is unit-testable off-device.

// MARK: - Hub tabs

/// The four chat-hub tabs, opened from the map chat bubble. The order is
/// load-bearing — it is BOTH the tab-strip order AND the page order — so it is
/// pinned by ``allCases`` (declaration order) exactly like Android's
/// `enum class ChatTab { Community, Convoys, Friends, Notifications }`, whose
/// `ordinal` addresses the pager.
///
/// Community and Convoys are functional in this slice; Friends (the existing
/// 1:1 DMs) and Notifications (the in-app inbox) are SEPARATE features that wire
/// up when those land — here they render a placeholder. Android reuses its
/// `dm` / `notifications` packages for those two tabs.
enum ChatTab: Int, CaseIterable, Sendable {
    case community
    case convoys
    case friends
    case notifications

    /// The tab shown on first entry. Android's hub defaults to Community.
    static let defaultTab: ChatTab = .community

    /// Whether this tab has a working implementation in this slice. Friends and
    /// Notifications are deferred to their own features.
    var isImplemented: Bool {
        switch self {
        case .community, .convoys: return true
        case .friends, .notifications: return false
        }
    }
}

// MARK: - Convoy row model

/// One convoy the caller can chat in (an ACCEPTED-member convoy), as surfaced to
/// the Convoys tab. Android: `ChatConvoy`.
struct ChatConvoy: Equatable, Sendable, Identifiable {
    let convoyId: String
    let title: String?
    /// `forming` / `active` / `ended` — the raw convoy status wire string.
    let status: String
    let memberCount: Int
    /// Display names of the ACCEPTED members (owner included), in roster order —
    /// used to build a meaningful row title for otherwise-unnamed convoys. May
    /// be shorter than ``memberCount`` when some members have no display name.
    let memberNames: [String]
    /// When the convoy was created, or nil when the payload carried no parseable
    /// timestamp. Drives the row's created-at label and the newest-first
    /// ordering within each section.
    let createdAt: Date?

    var id: String { convoyId }

    init(
        convoyId: String,
        title: String? = nil,
        status: String,
        memberCount: Int,
        memberNames: [String] = [],
        createdAt: Date? = nil
    ) {
        self.convoyId = convoyId
        self.title = title
        self.status = status
        self.memberCount = memberCount
        self.memberNames = memberNames
        self.createdAt = createdAt
    }
}

/// UI-facing state of the caller's convoy list (the Convoys tab). The
/// `convoy-list` callable is a one-shot load (not a listener), so — unlike the
/// message streams — it carries an explicit retryable ``error`` case. Android:
/// `ConvoyListState`.
enum ConvoyListState: Equatable, Sendable {
    case loading
    /// The `convoy-list` callable failed transiently — retryable.
    case error
    case loaded([ChatConvoy])
}

// MARK: - convoy-list projection

/// Pure projection of a `convoy-list` payload into the chat-eligible convoy
/// rows: keeps only convoys the caller has ACCEPTED (owner included) — the only
/// ones whose chat rules/callables permit. ENDED convoys are KEPT (their channel
/// stays member-readable after the drive ends) so the list can show them as
/// history; the ongoing-vs-past split is a presentation concern
/// (``ConvoyRowFormat/group(_:)``), not a filter here. Android:
/// `ConvoyChatMapper`.
enum ConvoyChatMapper {
    static func chatEligibleConvoys(_ data: Any?) -> [ChatConvoy] {
        let map = data as? [String: Any]
        let convoys = map?["convoys"] as? [Any] ?? []
        return convoys.compactMap { raw -> ChatConvoy? in
            guard let entry = raw as? [String: Any] else { return nil }
            guard let convoyId = (entry["convoyId"] as? String), !convoyId.isEmpty else { return nil }
            let status = entry["status"] as? String ?? "forming"
            let viewer = entry["viewer"] as? [String: Any]
            guard (viewer?["inviteStatus"] as? String) == "accepted" else { return nil }

            // Accepted members (owner included) carry the names the row is titled
            // with — the denormalized roster the callable already returns, so no
            // per-row profile fetch. Fall back to the memberUids count when the
            // roster is absent/partial.
            let members = entry["members"] as? [Any] ?? []
            let acceptedNames: [String] = members.compactMap { member in
                guard let m = member as? [String: Any],
                    (m["inviteStatus"] as? String) == "accepted",
                    let name = m["displayName"] as? String, !name.isEmpty
                else { return nil }
                return name
            }
            let acceptedCount = members.reduce(0) { count, member in
                let accepted = ((member as? [String: Any])?["inviteStatus"] as? String) == "accepted"
                return count + (accepted ? 1 : 0)
            }
            let memberCount = acceptedCount > 0
                ? acceptedCount
                : (entry["memberUids"] as? [Any])?.filter { $0 is String }.count ?? 0

            return ChatConvoy(
                convoyId: convoyId,
                title: (entry["title"] as? String).flatMap { $0.isEmpty ? nil : $0 },
                status: status,
                memberCount: memberCount,
                memberNames: acceptedNames,
                createdAt: ChannelTime.parseIso(entry["createdAt"] as? String)
            )
        }
    }
}

// MARK: - Convoys tab presentation

/// Pure presentation helpers for the Convoys tab — grouping the caller's
/// convoys into ONGOING vs PAST and building each row's member label. Android:
/// `ConvoyRowFormat` (the date-formatting there stays in the view layer on
/// iOS via `Date.formatted`). Android-free so classification, sorting, and the
/// name summary are unit-testable.
enum ConvoyRowFormat {
    /// Which section a convoy belongs in: still live, or ended history.
    enum Phase: Equatable, Sendable {
        case ongoing
        case past
    }

    /// A convoy is ONGOING while `forming`/`active`, and PAST once `ended` —
    /// mirroring the backend's one-convoy-at-a-time rule (every non-ended
    /// accepted convoy is the caller's single live one, the rest history).
    static func phase(status: String) -> Phase {
        status == endedStatus ? .past : .ongoing
    }

    /// The two sections of the list, each newest-created first.
    struct Grouped: Equatable, Sendable {
        let ongoing: [ChatConvoy]
        let past: [ChatConvoy]
    }

    /// Splits `convoys` into ongoing and past sections, each sorted
    /// newest-created first (convoys without a timestamp sort last, stably).
    static func group(_ convoys: [ChatConvoy]) -> Grouped {
        let ongoing = convoys.filter { phase(status: $0.status) == .ongoing }
        let past = convoys.filter { phase(status: $0.status) == .past }
        return Grouped(ongoing: newestFirst(ongoing), past: newestFirst(past))
    }

    private static func newestFirst(_ convoys: [ChatConvoy]) -> [ChatConvoy] {
        convoys.enumerated()
            .sorted { lhs, rhs in
                let l = lhs.element.createdAt ?? .distantPast
                let r = rhs.element.createdAt ?? .distantPast
                if l != r { return l > r }
                return lhs.offset < rhs.offset  // stable among ties / missing dates
            }
            .map(\.element)
    }

    /// The member portion of a row title: up to `maxShown` display names, plus a
    /// count of everyone beyond them. Blank names are dropped. ``MemberLabel/shownNames``
    /// is empty when no names are available at all, in which case the row falls
    /// back to a plain member count.
    struct MemberLabel: Equatable, Sendable {
        let shownNames: [String]
        let overflow: Int
    }

    static func memberLabel(names: [String], maxShown: Int = maxNamesShown) -> MemberLabel {
        let clean = names.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        let shown = Array(clean.prefix(maxShown))
        return MemberLabel(shownNames: shown, overflow: max(0, clean.count - shown.count))
    }

    private static let endedStatus = "ended"
    private static let maxNamesShown = 2
}
