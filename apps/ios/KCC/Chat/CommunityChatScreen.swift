import SwiftUI

/// The app-wide community channel chat — the iOS slice of Android's
/// `CommunityChannelRoute`. A thin wrapper over the shared ``ChannelChatScreen``
/// titled with `chatHub.tabCommunity`. Rendered as the Community tab inside
/// ``ChatHubScreen`` (community chat is a hub TAB, not its own shell route).
struct CommunityChatScreen: View {
    let coordinator: ChannelChatCoordinator?

    var body: some View {
        ChannelChatScreen(coordinator: coordinator, title: Text("chatHub.tabCommunity"))
    }
}

#Preview {
    NavigationStack {
        CommunityChatScreen(coordinator: nil)
    }
}
