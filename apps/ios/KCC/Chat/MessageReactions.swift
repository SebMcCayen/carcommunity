import CryptoKit
import CoreFoundation
import FirebaseAuth
import FirebaseCore
import FirebaseFirestore
import FirebaseFunctions
import Foundation
import SwiftUI

struct MessageReactionRoom: Equatable, Sendable {
    let scope: String
    let roomID: String
    var key: String { scope + ":" + roomID }
}
struct MessageReactionChoice: Codable, Equatable, Sendable {
    let emojiID: String?
    let version: Int
    var fetchedAt = Date().timeIntervalSince1970
}
struct ReactionEmoji: Identifiable, Decodable, Sendable {
    let id: String
    let glyph: String
    let name: String
    let keywords: String
    let category: String
    init(from decoder: Decoder) throws {
        var values = try decoder.unkeyedContainer()
        id = try values.decode(String.self); glyph = try values.decode(String.self)
        let localized = try values.decode(String.self)
        keywords = try values.decode(String.self); category = try values.decode(String.self)
        let english = try values.decodeIfPresent(String.self) ?? localized
        name = Locale.current.language.languageCode?.identifier == "sv" ? localized : english
    }
}
enum MessageReactionParser {
    static func counts(_ raw: Any?) -> [String: Int] {
        guard let map = raw as? [String: Any] else { return [:] }
        return map.reduce(into: [:]) { result, entry in
            guard entry.key.range(of: "\\A[a-f0-9-]{1,100}\\z", options: .regularExpression) != nil,
                  let number = entry.value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID(),
                  number.doubleValue > 0, number.doubleValue <= 9_007_199_254_740_991,
                  number.doubleValue == Double(number.int64Value) else { return }
            result[entry.key] = number.intValue
        }
    }
    static func ranking(_ usage: [String: ReactionUsage]) -> [String] {
        usage.keys.sorted { a, b in
            let left = usage[a]!, right = usage[b]!
            if left.count != right.count { return left.count > right.count }
            if left.lastUsed != right.lastUsed { return left.lastUsed > right.lastUsed }
            return a < b
        }
    }
}
struct ReactionUsage: Codable, Equatable, Sendable { var count: Int; var lastUsed: TimeInterval }

@MainActor @Observable
final class ReactionLocalHistory {
    private static var stores: [String: ReactionLocalHistory] = [:]
    static func store(uid: String) -> ReactionLocalHistory {
        if let existing = stores[uid] { return existing }
        let store = ReactionLocalHistory(uid: uid); stores[uid] = store; return store
    }
    private struct Disk: Codable { var usage: [String: ReactionUsage] = [:]; var own: [String: MessageReactionChoice] = [:] }
    private let url: URL
    private var disk = Disk()
    var usage: [String: ReactionUsage] { disk.usage }
    private init(uid: String) {
        let folder = URL.applicationSupportDirectory.appending(path: "EmojiReactions", directoryHint: .isDirectory)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        var directory = folder
        var resourceValues = URLResourceValues(); resourceValues.isExcludedFromBackup = true
        try? directory.setResourceValues(resourceValues)
        let key = SHA256.hash(data: Data(uid.utf8)).map { String(format: "%02x", $0) }.joined()
        url = folder.appending(path: key + ".json")
        if let bytes = try? Data(contentsOf: url), let saved = try? JSONDecoder().decode(Disk.self, from: bytes) { disk = saved }
    }
    func cached(_ room: MessageReactionRoom, id: String) -> MessageReactionChoice? {
        guard let state = disk.own[room.key + ":" + id], (0...60).contains(Date().timeIntervalSince1970 - state.fetchedAt) else { return nil }
        return state
    }
    func remember(_ room: MessageReactionRoom, id: String, choice: MessageReactionChoice) {
        disk.own[room.key + ":" + id] = choice
        while disk.own.count > 500, let oldest = disk.own.min(by: { $0.value.fetchedAt < $1.value.fetchedAt })?.key { disk.own.removeValue(forKey: oldest) }
    }
    func record(_ id: String) {
        disk.usage[id] = ReactionUsage(count: min((disk.usage[id]?.count ?? 0) + 1, 1_000_000_000), lastUsed: Date().timeIntervalSince1970)
    }
    func forget(_ room: MessageReactionRoom) { disk.own = disk.own.filter { !$0.key.hasPrefix(room.key + ":") } }
    func save() {
        guard let bytes = try? JSONEncoder().encode(disk) else { return }
        try? bytes.write(to: url, options: [.atomic, .completeFileProtection])
    }
    func clear() { disk = Disk(); try? FileManager.default.removeItem(at: url) }
}

@MainActor @Observable
final class MessageReactionStore {
    let room: MessageReactionRoom
    let uid: String
    let history: ReactionLocalHistory
    var enabled = false
    var catalog: [ReactionEmoji] = []
    var own: [String: MessageReactionChoice] = [:]
    var selectedID: String?
    var picker = false
    var busy = false
    var failed = false
    fileprivate var visible: [String: [String: Int]] = [:]
    private var attempted: [String: TimeInterval] = [:]
    private var generation = 0
    private var flags: ListenerRegistration?
    private var authHandle: AuthStateDidChangeListenerHandle?
    private var refresh: Task<Void, Never>?
    private var pending: (id: String, emoji: String?, version: Int, operation: String)?
    private var recorded: Set<String> = []
    init(room: MessageReactionRoom, uid: String) { self.room = room; self.uid = uid; history = .store(uid: uid) }
    private var current: Bool { FirebaseApp.app() != nil && Auth.auth().currentUser?.uid == uid }
    private var functions: Functions { Functions.functions(region: "europe-west1") }
    private var setName: String { room.scope == "club" ? "driver-clubsSetMessageReaction" : "chatReactions-set" }
    private var mineName: String { room.scope == "club" ? "driver-clubsGetMyMessageReactions" : "chatReactions-getMine" }
    func start() async {
        generation += 1
        let epoch = generation
        if catalog.isEmpty, let url = Bundle.main.url(forResource: "EmojiCatalog", withExtension: "json") {
            struct Catalog: Decodable, Sendable { let entries: [ReactionEmoji] }
            catalog = (try? await Task.detached { try JSONDecoder().decode(Catalog.self, from: Data(contentsOf: url)).entries }.value) ?? []
        }
        guard !Task.isCancelled, generation == epoch, FirebaseApp.app() != nil, flags == nil else { return }
        flags = Firestore.firestore().document("config/featureFlags").addSnapshotListener { [weak self] snapshot, error in
            let allowed = error == nil && snapshot?.get("chatReactions") as? Bool == true
            Task { @MainActor in
                guard let self else { return }
                self.enabled = allowed && self.current
                if self.enabled { self.request() } else { self.dismiss() }
            }
        }
        authHandle = Auth.auth().addStateDidChangeListener { [weak self] _, user in
            let uid = user?.uid
            Task { @MainActor in
                guard let self, uid != self.uid else { return }
                self.stop(); self.history.clear()
            }
        }
    }
    func stop() {
        generation += 1
        flags?.remove(); flags = nil
        if let authHandle, FirebaseApp.app() != nil { Auth.auth().removeStateDidChangeListener(authHandle) }; authHandle = nil
        refresh?.cancel(); enabled = false; dismiss(); own = [:]
    }
    func dismiss() { selectedID = nil; picker = false; failed = false }
    func select(_ id: String) { guard enabled, visible[id] != nil else { return }; selectedID = id; failed = false; request() }
    func reload() { if let id = selectedID { own[id] = nil; attempted[id] = nil }; failed = false; request() }
    func register(_ id: String, counts: [String: Int]) {
        if let old = visible[id], old != counts { own[id] = nil; attempted[id] = nil; history.forget(room) }
        visible[id] = counts
        if own[id] == nil { own[id] = history.cached(room, id: id) }
        request()
    }
    func unregister(_ id: String) { visible[id] = nil; own[id] = nil; attempted[id] = nil; if selectedID == id { dismiss() } }
    func request() {
        refresh?.cancel()
        refresh = Task { [weak self] in
            do { try await Task.sleep(for: .milliseconds(100)) } catch { return }
            guard let self, self.enabled, self.current, !self.busy else { return }
            let now = Date().timeIntervalSince1970
            let ids = Array(self.visible.keys.filter { now - (self.attempted[$0] ?? 0) > 10 && (self.own[$0] == nil || now - self.own[$0]!.fetchedAt > 60) }.prefix(50))
            guard !ids.isEmpty else { return }
            ids.forEach { self.attempted[$0] = now }
            do {
                let response = try await self.functions.httpsCallable(self.mineName).call(["scope": self.room.scope, "roomId": self.room.roomID, "messageIds": ids])
                guard self.current, let items = (response.data as? [String: Any])?["items"] as? [[String: Any]] else { return }
                for item in items {
                    guard let id = item["messageId"] as? String, ids.contains(id), self.visible[id] != nil,
                          let version = item["version"] as? Int, version >= 0 else { continue }
                    let choice = MessageReactionChoice(emojiID: item["emojiId"] as? String, version: version)
                    self.own[id] = choice; self.history.remember(self.room, id: id, choice: choice)
                }
                self.history.save()
            } catch is CancellationError { return } catch { self.own = [:]; self.history.forget(self.room); self.failed = self.selectedID != nil }
        }
    }
    func choose(_ emojiID: String?) {
        guard current, let id = selectedID, visible[id] != nil, let before = own[id], !busy else { failed = true; request(); return }
        let desired = before.emojiID == emojiID ? nil : emojiID
        guard before.emojiID != desired else { dismiss(); return }
        let operation: String, expectedVersion: Int
        if let pending, pending.id == id, pending.emoji == desired { operation = pending.operation; expectedVersion = pending.version }
        else {
            operation = UUID().uuidString
            expectedVersion = before.version
        }
        pending = (id, desired, expectedVersion, operation); busy = true; failed = false
        Task {
            defer { busy = false; if failed { request() } }
            do {
                // Construct a fresh SDK payload; the retained retry state contains only Sendable values.
                let response = try await functions.httpsCallable(setName).call(["scope": room.scope, "roomId": room.roomID, "messageId": id, "emojiId": desired as Any? ?? NSNull(), "expectedVersion": expectedVersion, "operationId": operation])
                guard current, visible[id] != nil, let data = response.data as? [String: Any], let version = data["version"] as? Int else { return }
                let choice = MessageReactionChoice(emojiID: data["emojiId"] as? String, version: version)
                visible[id] = MessageReactionParser.counts(data["counts"])
                own[id] = choice; history.remember(room, id: id, choice: choice)
                if let desired, choice.emojiID == desired, recorded.insert(operation).inserted { history.record(desired) }
                if recorded.count > 100, let first = recorded.first { recorded.remove(first) }
                pending = nil; history.save(); dismiss()
            } catch {
                let code = FunctionsErrorCode(rawValue: (error as NSError).code)
                if code == .aborted || code == .permissionDenied || code == .notFound || code == .failedPrecondition { pending = nil }
                failed = true; own[id] = nil; attempted[id] = nil; history.forget(room)
            }
        }
    }
}

private struct MessageReactionEnvironmentKey: EnvironmentKey { static let defaultValue: MessageReactionStore? = nil }
extension EnvironmentValues {
    var messageReactionStore: MessageReactionStore? {
        get { self[MessageReactionEnvironmentKey.self] }
        set { self[MessageReactionEnvironmentKey.self] = newValue }
    }
}
private struct ReactionAnchor {
    let anchor: Anchor<CGRect>
    let actions: AnyView
}
private struct ReactionAnchorKey: PreferenceKey {
    static var defaultValue: [String: ReactionAnchor] { [:] }
    static func reduce(value: inout [String: ReactionAnchor], nextValue: () -> [String: ReactionAnchor]) { value.merge(nextValue(), uniquingKeysWith: { _, newest in newest }) }
}

@MainActor
struct MessageReactionHost<Content: View>: View {
    @Environment(\.chatReadingActive) private var readingActive
    @Environment(\.scenePhase) private var scenePhase
    @State private var store: MessageReactionStore
    @ViewBuilder let content: () -> Content
    init(room: MessageReactionRoom, uid: String, @ViewBuilder content: @escaping () -> Content) {
        _store = State(initialValue: MessageReactionStore(room: room, uid: uid)); self.content = content
    }
    var body: some View {
        content().environment(\.messageReactionStore, store)
            .overlayPreferenceValue(ReactionAnchorKey.self) { anchors in
                GeometryReader { geometry in
                    if store.enabled, let id = store.selectedID, let selected = anchors[id] {
                        let rect = geometry[selected.anchor]
                        let width = min(360.0, max(240, geometry.size.width - 24))
                        let x = min(max(rect.midX, width / 2 + 12), geometry.size.width - width / 2 - 12)
                        Color.black.opacity(0.12).ignoresSafeArea().contentShape(Rectangle()).onTapGesture { store.dismiss() }
                        ReactionQuickBar(store: store, width: width)
                            .frame(width: width).position(x: x, y: max(32, rect.minY - 36))
                        VStack(spacing: KccSpacing.s2) {
                            HStack(spacing: KccSpacing.s2) { selected.actions }
                                .labelStyle(ReactionActionLabelStyle()).buttonStyle(.plain)
                            if store.failed { Button("reactions.error") { store.reload() }.font(.footnote).foregroundStyle(KccTheme.error) }
                            if store.busy { ProgressView() }
                        }
                        .padding(KccSpacing.s3).frame(width: width)
                        .background(KccTheme.surface, in: RoundedRectangle(cornerRadius: KccRadius.lg))
                        .overlay(RoundedRectangle(cornerRadius: KccRadius.lg).stroke(KccTheme.border))
                        .position(x: x, y: min(geometry.size.height - 52, rect.maxY + (store.visible[id]?.isEmpty == false ? 48 : 0) + 52))
                    }
                }
            }
            .sheet(isPresented: $store.picker) { ReactionEmojiPicker(store: store) }
            .task(id: readingActive && scenePhase == .active) {
                if readingActive && scenePhase == .active { await store.start() } else { store.stop() }
            }
            .onDisappear { store.stop() }
            .environment(\.messageReactionStore, store)
    }
}

private struct ReactionActionLabelStyle: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        VStack(spacing: KccSpacing.s1) { configuration.icon; configuration.title.font(.caption) }
            .frame(minWidth: 64, minHeight: 48)
    }
}
private struct MessageReactionModifier: ViewModifier {
    @Environment(\.messageReactionStore) private var store
    let id: String
    let counts: [String: Int]
    let eligible: Bool
    let isOwn: Bool
    let leadingInset: CGFloat
    let actions: AnyView
    func body(content: Content) -> some View {
        if let store, store.enabled, eligible {
            VStack(alignment: isOwn ? .trailing : .leading, spacing: KccSpacing.s1) {
                content.contentShape(Rectangle())
                    .onLongPressGesture { store.select(id) }
                    .accessibilityAction(named: Text("reactions.react")) { store.select(id) }
                    .anchorPreference(key: ReactionAnchorKey.self, value: .bounds) { [id: ReactionAnchor(anchor: $0, actions: actions)] }
                ReactionChips(store: store, id: id, counts: counts)
                    .padding(.leading, isOwn ? 0 : leadingInset)
            }
            .task(id: counts.keys.sorted().map { $0 + ":" + String(counts[$0] ?? 0) }.joined(separator: ";")) { store.register(id, counts: counts) }
            .onDisappear { store.unregister(id) }
        } else { content.contextMenu { actions } }
    }
}
extension View {
    func messageReactions<Actions: View>(id: String, counts: [String: Int], eligible: Bool = true, isOwn: Bool = false, leadingInset: CGFloat = 0, @ViewBuilder actions: () -> Actions) -> some View {
        modifier(MessageReactionModifier(id: id, counts: counts, eligible: eligible, isOwn: isOwn, leadingInset: leadingInset, actions: AnyView(actions())))
    }
}

private struct ReactionChips: View {
    let store: MessageReactionStore
    let id: String
    let counts: [String: Int]
    var body: some View {
        let sorted = counts.sorted { $0.value == $1.value ? $0.key < $1.key : $0.value > $1.value }
        HStack(spacing: KccSpacing.s1) {
            ForEach(Array(sorted.prefix(3)), id: \.key) { entry in
                if let emoji = store.catalog.first(where: { $0.id == entry.key }) {
                    let selected = store.own[id]?.emojiID == entry.key
                    Button { store.select(id); store.choose(entry.key) } label: {
                        HStack(spacing: 3) { Text(emoji.glyph); Text(entry.value.formatted()); if selected { Image(systemName: "checkmark") } }
                            .padding(.horizontal, KccSpacing.s2).frame(minHeight: 44)
                            .background(KccTheme.subtle, in: Capsule())
                            .overlay(Capsule().stroke(selected ? KccPalette.crownGold : KccTheme.border))
                    }
                    .buttonStyle(.plain).accessibilityLabel(emoji.name + ", " + String(entry.value))
                    .accessibilityAddTraits(selected ? .isSelected : [])
                }
            }
            if sorted.count > 3 { Button("reactions.more") { store.select(id); store.picker = true }.frame(minHeight: 44) }
        }
    }
}
private struct ReactionQuickBar: View {
    let store: MessageReactionStore
    let width: CGFloat
    var body: some View {
        let defaults = ["1f44d", "2764-fe0f", "1f602", "1f60a", "1f451"]
        let ranking = MessageReactionParser.ranking(store.history.usage) + defaults
        let unique = ranking.reduce(into: [String]()) { if !$0.contains($1) { $0.append($1) } }
        let count = max(1, min(5, Int((width - 8) / 48) - 1))
        HStack(spacing: 0) {
            ForEach(Array(unique.prefix(count)), id: \.self) { key in
                if let emoji = store.catalog.first(where: { $0.id == key }) { ReactionEmojiButton(emoji: emoji, store: store) }
            }
            Button { store.picker = true } label: { Image(systemName: "plus").font(.title2).frame(width: 48, height: 48) }
                .buttonStyle(.plain).accessibilityLabel(Text("reactions.all"))
        }
        .padding(4).frame(maxWidth: .infinity)
        .background(KccTheme.surface, in: RoundedRectangle(cornerRadius: KccRadius.lg))
        .overlay(RoundedRectangle(cornerRadius: KccRadius.lg).stroke(KccTheme.border))
    }
}
private struct ReactionEmojiButton: View {
    let emoji: ReactionEmoji
    let store: MessageReactionStore
    var body: some View {
        Button { store.choose(emoji.id) } label: { Text(emoji.glyph).font(.system(size: 25)).frame(width: 48, height: 48)
                .background(KccTheme.subtle, in: Circle())
                .overlay(Circle().stroke(store.own[store.selectedID ?? ""]?.emojiID == emoji.id ? KccPalette.crownGold : KccTheme.border)) }
            .buttonStyle(.plain).disabled(store.busy || store.own[store.selectedID ?? ""] == nil)
            .accessibilityLabel(emoji.name)
    }
}
private struct ReactionEmojiPicker: View {
    let store: MessageReactionStore
    @State private var search = ""
    @State private var category: String?
    var body: some View {
        NavigationStack {
            ScrollView {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack {
                        Button("reactions.all") { category = nil }.frame(minHeight: 44)
                        ForEach(Array(Set(store.catalog.map(\.category))).sorted(), id: \.self) { name in
                            Button(LocalizedStringKey(reactionCategoryKey(name))) { category = name }.frame(minHeight: 44)
                        }
                    }.padding(.horizontal, KccSpacing.s3)
                }
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 48))], spacing: KccSpacing.s1) {
                    if search.isEmpty {
                        Section {
                            ForEach(MessageReactionParser.ranking(store.history.usage).prefix(20), id: \.self) { key in
                                if let emoji = store.catalog.first(where: { $0.id == key }) { ReactionEmojiButton(emoji: emoji, store: store) }
                            }
                        } header: { Text("reactions.frequent").font(.headline).frame(maxWidth: .infinity, alignment: .leading) }
                    }
                    Section {
                        ForEach(store.catalog.filter { (category == nil || $0.category == category) && (search.isEmpty || $0.keywords.localizedCaseInsensitiveContains(search) || $0.glyph == search) }) { emoji in
                            ReactionEmojiButton(emoji: emoji, store: store)
                        }
                    } header: { Text("reactions.all").font(.headline).frame(maxWidth: .infinity, alignment: .leading) }
                }.padding(KccSpacing.s3)
            }
            .searchable(text: $search, prompt: Text("reactions.search"))
            .navigationTitle(Text("reactions.all"))
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("moderation.close") { store.picker = false } } }
            .safeAreaInset(edge: .bottom) { if store.failed { Button("reactions.error") { store.reload() }.foregroundStyle(KccTheme.error).padding() } }
        }
    }
}

func reactionCategoryKey(_ name: String) -> String {
    let names = ["Smileys & Emotion", "People & Body", "Component", "Animals & Nature", "Food & Drink", "Travel & Places", "Activities", "Objects", "Symbols", "Flags"]
    return "reactions.category" + String(names.firstIndex(of: name) ?? 0)
}

/// Uses the existing blocking boundary and confirmation; a visible label never grants authority.
struct ReactionReplyAction: View {
    @Environment(\.messageReactionStore) private var store
    let action: () -> Void
    var body: some View {
        Button { store?.dismiss(); action() } label: {
            Label("chat.replyQuoteHeaderAction", systemImage: "arrowshape.turn.up.left")
        }
    }
}

struct ReactionBlockAction: View {
    @Environment(\.messageReactionStore) private var store
    let targetUID: String
    @State private var confirming = false
    var body: some View {
        if let store, store.enabled {
        Button { confirming = true } label: { Label("blocking.blockUser", systemImage: "nosign") }
            .confirmationDialog(Text("blocking.blockConfirmTitle"), isPresented: $confirming, titleVisibility: .visible) {
                Button("blocking.blockConfirmAction", role: .destructive) {
                    Task {
                        guard FirebaseApp.app() != nil, Auth.auth().currentUser?.uid == store.uid,
                              let repository = FirebaseBlockingRepository.createIfAvailable() else { return }
                        do { try await repository.block(targetUserId: targetUID); store.dismiss() }
                        catch { store.failed = true }
                    }
                }
                Button("blocking.blockCancelAction", role: .cancel) {}
            } message: { Text("blocking.blockConfirmBody") }
        }
    }
}
struct ReactionReportAction: View {
    @Environment(\.messageReactionStore) private var store
    let messageID: String
    var body: some View {
        if let store, store.enabled {
        Menu {
            ForEach(ChatReportReason.allCases, id: \.self) { reason in
                Button(LocalizedStringKey("chat.reportReason." + reason.wire)) {
                    Task {
                        guard FirebaseApp.app() != nil, Auth.auth().currentUser?.uid == store.uid else { return }
                        do {
                            _ = try await Functions.functions(region: "europe-west1").httpsCallable("dm-reportMessage").call([
                                "conversationId": store.room.roomID, "messageId": messageID, "reason": reason.wire
                            ])
                            store.dismiss()
                        } catch { store.failed = true }
                    }
                }
            }
        } label: { Label("chat.reportMessage", systemImage: "flag") }
        }
    }
}
