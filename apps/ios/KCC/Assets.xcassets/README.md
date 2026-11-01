# iOS App Store icon

AppIcon uses Android's existing crown-only adaptive icon layers:
`drawable-nodpi/ic_launcher_background.png` and `ic_launcher_foreground.png`.
`swift apps/ios/scripts/generate-app-icon.swift` composites their central 72/108
viewport onto an opaque 1024×1024 sRGB canvas. The removed border is Android's
adaptive-icon motion padding. No new artwork or wordmark is introduced. iOS applies
its own platform mask; the source image has no rounded corners or alpha channel.
The native asset catalogue supplies all iPhone sizes from this universal source.
Source preflight rejects a missing, incorrectly sized or alpha-channel icon;
Organizer validation of the signed archive is still required.
