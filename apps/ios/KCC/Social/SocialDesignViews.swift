import SwiftUI

/// Native reading states stay scrollable even with the largest text setting.
struct SocialReadingState: View {
    let title: LocalizedStringKey
    let message: LocalizedStringKey
    let symbol: String

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: KccSpacing.s3) {
                Image(systemName: symbol).font(.title).foregroundStyle(KccTheme.driver)
                    .accessibilityHidden(true)
                Text(title).font(.title2.bold()).accessibilityAddTraits(.isHeader)
                Text(message).font(.body).foregroundStyle(KccTheme.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .kccContentCard()
            .padding(KccSpacing.s4)
        }
    }
}

/// Stack identities and actions at accessibility sizes instead of squeezing names.
struct SocialFlow<Content: View>: View {
    @Environment(\.dynamicTypeSize) private var typeSize
    @ViewBuilder let content: () -> Content
    var body: some View {
        let layout = typeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: KccSpacing.s3))
            : AnyLayout(HStackLayout(spacing: KccSpacing.s3))
        layout { content() }
    }
}

struct SocialFlowSpacer: View {
    @Environment(\.dynamicTypeSize) private var typeSize
    var body: some View {
        if !typeSize.isAccessibilitySize { Spacer(minLength: 0) }
    }
}

struct FriendRowContent: View {
    let friend: FriendSummary
    let points: Int64
    var onMessage: (() -> Void)?
    var onProfile: (() -> Void)?

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s3) {
            SocialMemberLabel(name: friend.displayName)
            SocialFlow {
                Text(verbatim: FriendPointsFormat.grouped(points)).fontWeight(.semibold)
                Text("profile.pointsTitle")
            }
            .font(.caption).foregroundStyle(KccTheme.secondary)
            .padding(KccSpacing.s2)
            .background(KccTheme.subtle, in: RoundedRectangle(cornerRadius: KccRadius.sm))
            SocialFlow {
                if let onMessage {
                    Button(action: onMessage) {
                        Text("friends.message").frame(minHeight: 48)
                    }.buttonStyle(.bordered)
                }
                if let onProfile {
                    Button(action: onProfile) {
                        Image(systemName: "person.crop.circle").frame(minWidth: 48, minHeight: 48)
                    }
                    .buttonStyle(.borderless)
                    .accessibilityLabel(Text("memberProfile.title"))
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct FriendRequestContent: View {
    let name: String?
    let busy: Bool
    var onAccept: (() -> Void)?
    let onDeclineOrCancel: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s3) {
            SocialMemberLabel(name: name)
            if onAccept == nil {
                Text("friends.outgoingPendingLabel").font(.caption).foregroundStyle(KccTheme.secondary)
            }
            SocialFlow {
                if let onAccept {
                    Button(action: onAccept) {
                        Text("friends.accept").frame(minHeight: 48)
                    }.kccPrimaryAction().disabled(busy)
                }
                Button(role: .destructive, action: onDeclineOrCancel) {
                    Text(onAccept == nil ? "friends.cancelRequestAction" : "friends.decline")
                        .frame(minHeight: 48)
                }.buttonStyle(.bordered).disabled(busy)
            }
        }
        // Independent buttons, never whole-row List activation.
        .buttonStyle(.borderless)
    }
}

/// A local fallback only; a design change must not introduce avatar lookups.
struct SocialMemberLabel: View {
    let name: String?
    var fallback: LocalizedStringKey = "friends.unknownMember"
    var body: some View {
        HStack(alignment: .top, spacing: KccSpacing.s3) {
            Image(systemName: "person.fill")
                .font(.system(size: 20)).foregroundStyle(KccTheme.driver)
                .frame(width: 40, height: 40)
                .background(KccTheme.subtle, in: Circle())
                .accessibilityHidden(true)
            Group {
                if let name = name.trimmedNonBlank { Text(verbatim: name) }
                else { Text(fallback) }
            }
            .font(.headline).foregroundStyle(KccTheme.text)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

struct DmComposer: View {
    @Binding var draft: String
    let onSend: (String) -> Void
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        HStack(alignment: .bottom, spacing: KccSpacing.s2) {
            TextField(text: $draft, axis: .vertical) { Text("dm.inputPlaceholder") }
                .font(.body)
                .lineLimit(typeSize.isAccessibilitySize ? 1 : 2)
                .padding(KccSpacing.s3)
                .frame(minHeight: 48)
                .background(KccTheme.subtle, in: RoundedRectangle(cornerRadius: KccRadius.md))
                .accessibilityIdentifier("dm.composer.input")
            Button {
                guard DmThreadLogic.isSendable(draft) else { return }
                let text = draft
                // The coordinator retains the optimistic message and its retry ID.
                draft = ""
                onSend(text)
            } label: {
                Image(systemName: "paperplane.fill")
                    .font(.body.weight(.semibold))
                    .frame(minWidth: 48, minHeight: 48)
                    .contentShape(Rectangle())
            }
            .kccPrimaryAction()
            .accessibilityLabel(Text("dm.send"))
            .accessibilityIdentifier("dm.composer.send")
            .disabled(!DmThreadLogic.isSendable(draft))
        }
        .padding(KccSpacing.s3)
        .background(KccTheme.surface)
    }
}
