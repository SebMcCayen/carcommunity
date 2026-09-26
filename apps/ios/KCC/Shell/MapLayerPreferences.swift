import Foundation
import Observation

/// Pure resting-zoom policy shared by persistence, the layers slider and the
/// renderer. Values match Android's `MapZoomPreference` byte for byte.
enum MapBrowsingZoom {
    static let minimum = 12.0
    static let maximum = 18.0
    static let defaultValue = 16.0
    static let step = 0.5

    static func clamp(_ zoom: Double) -> Double {
        min(max(zoom, minimum), maximum)
    }

    static func snap(_ zoom: Double) -> Double {
        guard zoom.isFinite else { return defaultValue }
        let notch = ((clamp(zoom) - minimum) / step).rounded()
        return clamp(minimum + notch * step)
    }

    static func fromStored(_ zoom: Double?) -> Double {
        guard let zoom, zoom.isFinite else { return defaultValue }
        return snap(zoom)
    }
}

struct MapTrafficColors: Equatable, Sendable {
    let low: UInt32
    let moderate: UInt32
    let heavy: UInt32
    let severe: UInt32
    let unknown: UInt32
}

/// Mapbox congestion ramps ported from Android's `TrafficPalette`. Night uses
/// wider, deeper lines that stay legible against the Standard dark basemap.
enum MapTrafficPalette {
    static let day = MapTrafficColors(
        low: 0xFF4CAF50,
        moderate: 0xFFFFC107,
        heavy: 0xFFFF6F00,
        severe: 0xFFD32F2F,
        unknown: 0xFF9E9E9E
    )
    static let night = MapTrafficColors(
        low: 0xFF11B076,
        moderate: 0xFFC9A227,
        heavy: 0xFFC20017,
        severe: 0xFFE65656,
        unknown: 0xFF757575
    )

    static func colors(for mode: MapMode) -> MapTrafficColors {
        mode == .night ? night : day
    }

    static func lineWidth(for mode: MapMode) -> Double {
        mode == .night ? 3.5 : 2.5
    }
}

/// Device-local map viewing choices. These are intentionally not account
/// state: the useful traffic/style/camera setup depends on this screen and
/// survives sign-out, just like Android's SharedPreferences-backed store.
@Observable
@MainActor
final class MapLayerPreferences {
    static let trafficDefault = false
    static let threeDDefault = true

    private enum Key {
        static let traffic = "map.layers.traffic"
        static let mode = "map.layers.mode"
        static let threeD = "map.layers.3d"
        static let zoom = "map.layers.browsingZoom"
    }

    @ObservationIgnored private let defaults: UserDefaults

    private(set) var trafficEnabled: Bool
    /// Nil means follow the current system/app appearance.
    private(set) var mapModeOverride: MapMode?
    private(set) var is3D: Bool
    private(set) var browsingZoom: Double

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        trafficEnabled = defaults.object(forKey: Key.traffic) as? Bool
            ?? Self.trafficDefault
        mapModeOverride = Self.decodeMapMode(defaults.string(forKey: Key.mode))
        is3D = defaults.object(forKey: Key.threeD) as? Bool
            ?? Self.threeDDefault
        browsingZoom = MapBrowsingZoom.fromStored(
            defaults.object(forKey: Key.zoom) as? Double
        )
    }

    func effectiveMapMode(systemIsDark: Bool) -> MapMode {
        mapModeOverride ?? (systemIsDark ? .night : .day)
    }

    func setTrafficEnabled(_ enabled: Bool) {
        guard enabled != trafficEnabled else { return }
        trafficEnabled = enabled
        defaults.set(enabled, forKey: Key.traffic)
    }

    func setMapModeOverride(_ mode: MapMode?) {
        guard mode != mapModeOverride else { return }
        mapModeOverride = mode
        if let mode {
            defaults.set(mode.rawValue, forKey: Key.mode)
        } else {
            defaults.removeObject(forKey: Key.mode)
        }
    }

    func set3DEnabled(_ enabled: Bool) {
        guard enabled != is3D else { return }
        is3D = enabled
        defaults.set(enabled, forKey: Key.threeD)
    }

    func setBrowsingZoom(_ zoom: Double) {
        let snapped = MapBrowsingZoom.snap(zoom)
        guard snapped != browsingZoom else { return }
        browsingZoom = snapped
        defaults.set(snapped, forKey: Key.zoom)
    }

    static func decodeMapMode(_ stored: String?) -> MapMode? {
        stored.flatMap(MapMode.init(rawValue:))
    }
}
