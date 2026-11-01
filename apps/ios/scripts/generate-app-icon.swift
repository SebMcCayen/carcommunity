// Compile the existing Android adaptive-icon layers into iOS's opaque 1024px icon.
// Run from any directory: swift apps/ios/scripts/generate-app-icon.swift
import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

let ios = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
let layers = ios.deletingLastPathComponent()
    .appendingPathComponent("android/app/src/main/res/drawable-nodpi")
let output = ios.appendingPathComponent("KCC/Assets.xcassets/AppIcon.appiconset/AppIcon.png")
let side = 1024
let canvas = CGRect(x: 0, y: 0, width: side, height: side)

func loadLayer(_ name: String) throws -> CGImage {
    let url = layers.appendingPathComponent(name)
    guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
          let image = CGImageSourceCreateImageAtIndex(source, 0, nil),
          image.width == image.height else {
        throw NSError(domain: "KCCAppIcon", code: 1,
                      userInfo: [NSLocalizedDescriptionKey: "Invalid square icon layer: \(name)"])
    }
    // Android's 108dp adaptive layer exposes a central 72dp viewport. Remove
    // only its motion padding, preserving the existing crown and background.
    let inset = CGFloat(image.width) / 6
    let viewport = CGRect(x: inset, y: inset,
                          width: CGFloat(image.width) - 2 * inset,
                          height: CGFloat(image.height) - 2 * inset)
    guard let cropped = image.cropping(to: viewport) else {
        throw NSError(domain: "KCCAppIcon", code: 2)
    }
    return cropped
}

guard let context = CGContext(data: nil, width: side, height: side,
                              bitsPerComponent: 8, bytesPerRow: side * 4,
                              space: CGColorSpace(name: CGColorSpace.sRGB)!,
                              bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else {
    fatalError("Could not create opaque RGB icon canvas")
}
context.setFillColor(CGColor(gray: 0, alpha: 1))
context.fill(canvas)
context.interpolationQuality = .high
for name in ["ic_launcher_background.png", "ic_launcher_foreground.png"] {
    context.draw(try loadLayer(name), in: canvas)
}
guard let image = context.makeImage(),
      let destination = CGImageDestinationCreateWithURL(output as CFURL, UTType.png.identifier as CFString, 1, nil) else {
    fatalError("Could not create app icon PNG")
}
CGImageDestinationAddImage(destination, image, nil)
guard CGImageDestinationFinalize(destination) else { fatalError("Could not save app icon PNG") }
print("Generated opaque 1024px AppIcon from Android's crown layers")
