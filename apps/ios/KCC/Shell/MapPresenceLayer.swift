import SwiftUI

private struct MapPresentationActiveKey: EnvironmentKey { static let defaultValue = true }
extension EnvironmentValues {
    var mapPresentationActive: Bool {
        get { self[MapPresentationActiveKey.self] }
        set { self[MapPresentationActiveKey.self] = newValue }
    }
}

struct MapPresentItem<Value> {
    let id: String
    let value: Value
    let alpha: Double
    let interactive: Bool
}

@Observable @MainActor
private final class MapPresencePresentation<Value> {
    private(set) var frames: [MapPresencePolicy.Frame] = []
    @ObservationIgnored private var policy = MapPresencePolicy()
    @ObservationIgnored private var animation: Task<Void, Never>?
    @ObservationIgnored private var payloads: [String: Value] = [:]
    @ObservationIgnored private var input: Input?
    private struct Input: Equatable {
        let ids: Set<String>
        let allowed: Set<String>
        let placements: [String: Int]
        let active: Bool
        let animations: Bool
    }
    var trackedIDs: Set<String> { Set(payloads.keys) }
    func retained(_ id: String) -> Value? { payloads[id] }

    func update(_ items: [String: Value], allowed: Set<String>, active: Bool,
                animations: Bool, placements: [String: Int], retainPublic: Bool) {
        guard active else { stop(); return }
        payloads.merge(items) { _, current in current }
        let permitted = retainPublic ? allowed.union(payloads.keys) : allowed
        if !retainPublic { payloads = payloads.filter { permitted.contains($0.key) } }
        let next = Input(ids: Set(items.keys), allowed: permitted, placements: placements, active: active, animations: animations)
        guard next != input else { return }
        input = next
        animation?.cancel()
        policy.sync(visible: next.ids, allowed: permitted, now: ProcessInfo.processInfo.systemUptime,
            animations: animations, placements: placements)
        publish()
        guard policy.isAnimating(at: ProcessInfo.processInfo.systemUptime) else { return }
        animation = Task { @MainActor [weak self] in
            guard let self else { return }
            repeat {
                do { try await Task.sleep(for: .milliseconds(16)) } catch { return }
                guard !Task.isCancelled else { return }
                publish()
            } while policy.isAnimating(at: ProcessInfo.processInfo.systemUptime)
            publish() // A delayed first tick must still settle to the terminal frame.
        }
    }
    private func publish() {
        frames = policy.frames(at: ProcessInfo.processInfo.systemUptime)
        let keep = policy.trackedIDs.union(input?.ids ?? [])
        payloads = payloads.filter { keep.contains($0.key) }
    }
    func stop() {
        animation?.cancel(); animation = nil
        policy.clear(); payloads.removeAll(); frames = []; input = nil
    }
}

/// The fade affects an entire visual unit. Current values are read directly,
/// so it never introduces another geographic interpolation or position queue.
struct MapPresenceLayer<Value: Equatable, Content: View>: View {
    let items: [String: Value]
    var allowed: Set<String>? = nil
    var active = true
    var placements: [String: Int] = [:]
    var retainRemovedPublicItems = false
    @ViewBuilder let content: (MapPresentItem<Value>) -> Content
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.mapPresentationActive) private var mapActive
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var presentation = MapPresencePresentation<Value>()

    private var enabled: Bool { active && mapActive && scenePhase == .active }
    private var permitted: Set<String> { allowed ?? Set(items.keys) }
    var body: some View {
        ZStack {
            if enabled {
                ForEach(presentation.frames, id: \.id) { frame in
                    if (retainRemovedPublicItems || permitted.contains(frame.id)),
                       let value = items[frame.id] ?? presentation.retained(frame.id) {
                        let samePlacement = placements[frame.id].map { $0 == frame.placement } ?? true
                        let current = items[frame.id] != nil
                        content(MapPresentItem(id: frame.id, value: value,
                            alpha: samePlacement ? frame.alpha : 0,
                            interactive: samePlacement && current && frame.interactive))
                            .opacity(samePlacement ? frame.alpha : 0)
                            .allowsHitTesting(samePlacement && current && frame.interactive)
                            .accessibilityHidden(!samePlacement || !current || !frame.interactive)
                    }
                }
            }
        }
        .onChange(of: items, initial: true) { _, _ in update() }
        .onChange(of: permitted) { _, _ in update() }
        .onChange(of: placements) { _, _ in update() }
        .onChange(of: enabled) { _, _ in update() }
        .onChange(of: reduceMotion) { _, _ in update() }
        .onDisappear { presentation.stop() }
    }
    private func update() {
        presentation.update(items, allowed: permitted, active: enabled, animations: !reduceMotion,
            placements: placements, retainPublic: retainRemovedPublicItems)
    }
}
