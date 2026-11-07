import SwiftUI
import UIKit

/// Android ChatColors: gold fills with dark ink; text accents use reading ink
/// on light surfaces because gold alone does not meet normal-text contrast.
enum KccChatColors {
    static let readingAccent = Color(uiColor: UIColor { traits in
        UIColor(traits.userInterfaceStyle == .dark ? KccPalette.crownGold : KccLightColors.textPrimary)
    })
}
