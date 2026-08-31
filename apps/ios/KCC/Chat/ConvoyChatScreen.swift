import SwiftUI

/// One convoy's chat — the iOS slice of Android's `ConvoyChannelRoute`. A thin
/// wrapper over the shared ``ChannelChatScreen`` titled with the convoy's name
/// (falling back to `chatHub.convoyUntitled`). Pushed from the Convoys tab list
/// inside ``ChatHubScreen``.
struct ConvoyChatScreen: View {
    let coordinator: ChannelChatCoordinator?
    /// The convoy's display name for the title, or nil → `chatHub.convoyUntitled`.
    let convoyTitle: String?

    var body: some View {
        ChannelChatScreen(coordinator: coordinator, title: titleText)
    }

    private var titleText: Text {
        if let convoyTitle, !convoyTitle.isEmpty {
            // A runtime convoy name — verbatim, never treated as a localization
            // key.
            return Text(verbatim: convoyTitle)
        }
        return Text("chatHub.convoyUntitled")
    }
}

#Preview {
    NavigationStack {
        ConvoyChatScreen(coordinator: nil, convoyTitle: nil)
    }
}
