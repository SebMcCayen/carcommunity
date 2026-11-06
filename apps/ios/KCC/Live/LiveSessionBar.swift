import SwiftUI

/// Android's elapsed → current speed → distance dock. The ticker is local to
/// this view; readings reuse the live/drive coordinators' existing streams.
struct LiveSessionBar: View {
    let startedAt: Date
    let live: LiveLocationCoordinator
    let recording: DriveRecordingCoordinator?
    let onManage: () -> Void
    @State private var shownSpeed: Int?

    var body: some View {
        let id = live.isSharing ? live.readout.sessionId : nil
        TimelineView(.periodic(from: .now, by: 1)) { context in
            let elapsed = LiveSessionReadoutFormat.elapsed(startedAt: startedAt, now: context.date)
            let speed = LiveSessionReadoutFormat.speedKmh(id == nil ? nil : live.readout.speed, at: context.date, previous: shownSpeed)
            let speedText = speed.map { "\($0) km/h" } ?? "— km/h"
            let distanceMeters = id.flatMap { recording?.distanceMeters(for: $0) } ?? 0
            let distance = DriveFormatters.formatDistance(distanceMeters)
            let speedDescription = speed.map {
                String.localizedStringWithFormat(NSLocalizedString("liveLocation.sessionBarSpeed", comment: ""), $0)
            } ?? String(localized: "liveLocation.sessionBarSpeedUnknown")
            Button(action: onManage) {
                LiveMetricLayout {
                    metric(elapsed, icon: "record.circle.fill", id: "elapsed")
                    metric(speedText, icon: "speedometer", id: "speed")
                    metric(distance, icon: "ruler", id: "distance")
                }
                .padding(.horizontal, KccSpacing.s4)
                .padding(.vertical, KccSpacing.s2)
                .frame(maxWidth: .infinity, minHeight: 48)
                .foregroundStyle(KccTheme.text)
                .background(KccTheme.surface, in: RoundedRectangle(cornerRadius: KccRadius.full))
                .overlay { RoundedRectangle(cornerRadius: KccRadius.full).strokeBorder(KccTheme.border).allowsHitTesting(false) }
            }
            .buttonStyle(.plain)
            .disabled(id == nil)
            .accessibilityLabel(String.localizedStringWithFormat(
                NSLocalizedString("liveLocation.sessionBar", comment: ""), elapsed, speedDescription, distance))
            .accessibilityHint(Text("liveLocation.sessionBarManageHint"))
            .accessibilityIdentifier("map.session.bar")
            .onChange(of: speed, initial: true) { _, value in shownSpeed = value }
            .onChange(of: id) { _, _ in shownSpeed = nil }
        }
    }

    private func metric(_ text: String, icon: String, id: String) -> some View {
        HStack(spacing: KccSpacing.s1) {
            Image(systemName: icon).font(.system(size: 18))
                .foregroundStyle(id == "elapsed" ? KccPalette.errorRed : KccTheme.driver)
                .accessibilityHidden(true)
            Text(text).font(.subheadline.weight(.semibold)).monospacedDigit()
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity)
        .accessibilityIdentifier("map.session.\(id)")
    }
}

/// Pack natural metric widths in reading order, distributing each row's spare
/// width equally. Larger Dynamic Type wraps rows without hiding units.
private struct LiveMetricLayout: Layout {
    private let gap = KccSpacing.s2

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let natural = subviews.map { $0.sizeThatFits(.unspecified).width }
        let proposed = proposal.width.flatMap { $0.isFinite && $0 > 0 ? $0 : nil }
        let width = max(1, proposed ?? (natural.reduce(0, +) + gap * CGFloat(max(0, natural.count - 1))))
        let frames = arrange(width: width, subviews: subviews)
        return CGSize(width: width, height: frames.map(\.maxY).max() ?? 0)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        for (index, frame) in arrange(width: max(1, bounds.width), subviews: subviews).enumerated() {
            subviews[index].place(at: CGPoint(x: bounds.minX + frame.minX, y: bounds.minY + frame.minY),
                proposal: ProposedViewSize(frame.size))
        }
    }

    private func arrange(width: CGFloat, subviews: Subviews) -> [CGRect] {
        let preferred = subviews.map { min(width, $0.sizeThatFits(.unspecified).width) }
        var rows: [[Int]] = [], used: CGFloat = 0
        for index in preferred.indices {
            if rows.isEmpty || used + gap + preferred[index] > width {
                rows.append([]); used = 0
            }
            if !rows[rows.count - 1].isEmpty { used += gap }
            rows[rows.count - 1].append(index); used += preferred[index]
        }
        var frames = Array(repeating: CGRect.zero, count: subviews.count)
        var y: CGFloat = 0
        for row in rows {
            let extra = max(0, width - row.reduce(0) { $0 + preferred[$1] } - gap * CGFloat(row.count - 1)) / CGFloat(row.count)
            let sizes = row.map { subviews[$0].sizeThatFits(ProposedViewSize(width: preferred[$0] + extra, height: nil)) }
            let height = sizes.map(\.height).max() ?? 0
            var x: CGFloat = 0
            for (offset, index) in row.enumerated() {
                let cellWidth = preferred[index] + extra
                frames[index] = CGRect(x: x, y: y + (height - sizes[offset].height) / 2,
                    width: cellWidth, height: sizes[offset].height)
                x += cellWidth + gap
            }
            y += height + gap
        }
        return frames
    }
}
