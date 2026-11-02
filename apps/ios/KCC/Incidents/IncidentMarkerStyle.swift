import Foundation

/// Android IncidentMarkerStyle: one two-tone ring and category glyph that
/// remain legible on either basemap. Cleared reports fade only the disc.
enum IncidentMarkerStyle {
    static let dark: UInt32 = 0xFF1A1A1A
    static let light: UInt32 = 0xFFFFFFFF
    static let discDiameter: CGFloat = 26
    static let lightRingWidth: CGFloat = 2.5
    static let darkRingWidth: CGFloat = 1
    static let glyphScale: CGFloat = 0.58

    static func discColor(_ color: UInt32, reportedCleared: Bool) -> UInt32 {
        guard reportedCleared else { return color }
        func channel(_ shift: UInt32) -> UInt32 {
            // Android's source-over arithmetic truncates each sRGB channel.
            UInt32(Float((color >> shift) & 255) * 0.4 + 255 * 0.6)
        }
        return 0xFF000000 | channel(16) << 16 | channel(8) << 8 | channel(0)
    }

    static func glyphColor(on color: UInt32) -> UInt32 {
        contrast(light, color) >= contrast(dark, color) ? light : dark
    }

    static func contrast(_ a: UInt32, _ b: UInt32) -> Double {
        func luminance(_ color: UInt32) -> Double {
            func linear(_ shift: UInt32) -> Double {
                let c = Double((color >> shift) & 255) / 255
                return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * linear(16) + 0.7152 * linear(8) + 0.0722 * linear(0)
        }
        let x = luminance(a), y = luminance(b)
        return (max(x, y) + 0.05) / (min(x, y) + 0.05)
    }
}
