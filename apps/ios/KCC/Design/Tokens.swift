// GENERATED FILE — do not edit by hand.
// Source: contracts/design-tokens/tokens.json
// Regenerate: node apps/ios/scripts/generate-tokens.mjs

import SwiftUI

/// KCC Crown UI brand palette.
enum KccPalette {
    static let crownGold = Color(red: 234 / 255, green: 181 / 255, blue: 75 / 255)
    static let darkCharcoal = Color(red: 63 / 255, green: 62 / 255, blue: 59 / 255)
    static let inkBlack = Color(red: 4 / 255, green: 2 / 255, blue: 17 / 255)
    static let warmIvory = Color(red: 248 / 255, green: 246 / 255, blue: 239 / 255)
    static let softSand = Color(red: 240 / 255, green: 235 / 255, blue: 220 / 255)
    static let mutedGrey = Color(red: 109 / 255, green: 108 / 255, blue: 109 / 255)
    static let silverGrey = Color(red: 180 / 255, green: 177 / 255, blue: 173 / 255)
    static let successGreen = Color(red: 30 / 255, green: 142 / 255, blue: 62 / 255)
    static let errorRed = Color(red: 197 / 255, green: 34 / 255, blue: 31 / 255)
    static let warningAmber = Color(red: 230 / 255, green: 168 / 255, blue: 23 / 255)
}

/// Spacing scale (4pt base).
enum KccSpacing {
    static let s0: CGFloat = 0
    static let s1: CGFloat = 4
    static let s2: CGFloat = 8
    static let s3: CGFloat = 12
    static let s4: CGFloat = 16
    static let s5: CGFloat = 20
    static let s6: CGFloat = 24
    static let s8: CGFloat = 32
    static let s10: CGFloat = 40
    static let s12: CGFloat = 48
}

/// Corner radius scale.
enum KccRadius {
    static let sm: CGFloat = 8
    static let md: CGFloat = 12
    static let lg: CGFloat = 16
    static let xl: CGFloat = 24
    static let full: CGFloat = 9999
}

/// Type scale: sizes in points (user-scalable via Dynamic Type when used
/// with `Font.system(size:relativeTo:)`) and font weights.
enum KccTypeScale {
    static let caption: CGFloat = 12
    static let bodySm: CGFloat = 14
    static let bodyMd: CGFloat = 16
    static let titleMd: CGFloat = 18
    static let headingLg: CGFloat = 24
    static let regular = Font.Weight.regular
    static let medium = Font.Weight.medium
    static let semibold = Font.Weight.semibold
}

/// Semantic light-theme colors.
enum KccLightColors {
    static let pageBackground = Color(red: 248 / 255, green: 246 / 255, blue: 239 / 255)
    static let surfaceBackground = Color(red: 255 / 255, green: 255 / 255, blue: 255 / 255)
    static let subtleBackground = Color(red: 240 / 255, green: 235 / 255, blue: 220 / 255)
    static let textPrimary = Color(red: 4 / 255, green: 2 / 255, blue: 17 / 255)
    static let textSecondary = Color(red: 109 / 255, green: 108 / 255, blue: 109 / 255)
    static let borderDefault = Color(red: 180 / 255, green: 177 / 255, blue: 173 / 255)
    static let brandPrimary = Color(red: 234 / 255, green: 181 / 255, blue: 75 / 255)
    static let statusSuccess = Color(red: 30 / 255, green: 142 / 255, blue: 62 / 255)
    static let statusError = Color(red: 197 / 255, green: 34 / 255, blue: 31 / 255)
    static let statusWarning = Color(red: 230 / 255, green: 168 / 255, blue: 23 / 255)
}

/// Semantic dark-theme colors.
enum KccDarkColors {
    static let pageBackground = Color(red: 4 / 255, green: 2 / 255, blue: 17 / 255)
    static let surfaceBackground = Color(red: 63 / 255, green: 62 / 255, blue: 59 / 255)
    static let subtleBackground = Color(red: 42 / 255, green: 41 / 255, blue: 39 / 255)
    static let textPrimary = Color(red: 248 / 255, green: 246 / 255, blue: 239 / 255)
    static let textSecondary = Color(red: 180 / 255, green: 177 / 255, blue: 173 / 255)
    static let borderDefault = Color(red: 109 / 255, green: 108 / 255, blue: 109 / 255)
    static let brandPrimary = Color(red: 234 / 255, green: 181 / 255, blue: 75 / 255)
    static let statusSuccess = Color(red: 30 / 255, green: 142 / 255, blue: 62 / 255)
    static let statusError = Color(red: 197 / 255, green: 34 / 255, blue: 31 / 255)
    static let statusWarning = Color(red: 230 / 255, green: 168 / 255, blue: 23 / 255)
}
