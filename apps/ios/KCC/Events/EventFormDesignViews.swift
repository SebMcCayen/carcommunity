import SwiftUI

struct EventLocationSummary: View {
    let latitude: Double?
    let longitude: Double?

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s2) {
            if let latitude, let longitude {
                let coordinates = String(format: "%.5f, %.5f", latitude, longitude)
                Text(verbatim: String.localizedStringWithFormat(
                    NSLocalizedString("events.createLocationSet", comment: "Public event pin disclosure"), coordinates
                ))
                .font(.body).fixedSize(horizontal: false, vertical: true)
            } else {
                Text("events.createLocationNone").font(.body).fixedSize(horizontal: false, vertical: true)
            }
        }
        .foregroundStyle(KccTheme.secondary).frame(maxWidth: .infinity, alignment: .leading)
    }
}
