# iOS App Store icon

AppIcon uses the existing KCC artwork from Android's
`drawable-nodpi/kcc_logo_dark_bg.png`, fitted without cropping to an opaque black
1024×1024 RGB canvas. No new logo or licensed artwork is introduced. iOS applies
its own platform mask; the source image has no rounded corners or alpha channel.
The native asset catalogue supplies all iPhone sizes from this universal source.
Source preflight rejects a missing, incorrectly sized or alpha-channel icon;
Organizer validation of the signed archive is still required.
