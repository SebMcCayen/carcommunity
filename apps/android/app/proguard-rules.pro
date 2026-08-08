# Project-specific ProGuard/R8 rules for the release (minified + shrunk) build.
#
# Keep the source-file name and line-number table, and rename the source-file
# attribute to a constant, so de-obfuscated crash/ANR stack traces (retraced via
# mapping.txt in the Play/Firebase consoles) show real line numbers instead of
# "Unknown Source". This is metadata only and does not defeat shrinking/obfuscation.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# No app-specific -keep rules are required:
#  - Firestore/RTDB reads are manual (snapshot.get / getValue on primitives) — no
#    reflective POJO deserialization (no .toObject / GenericTypeIndicator), so no
#    model classes need keeping.
#  - The name-persisted enums (ThemePreference, MapCompassMode, MapMode) match on
#    Enum.name via entries.find { it.name == ... }; R8 preserves enum constant
#    name strings and does not unbox enums whose .name/.entries are used, so the
#    round-trip is safe without a keep.
#  - No custom android.view.View is referenced from XML (no XML layouts); manifest
#    components are kept by AGP automatically.
#  - No Class.forName / name-based reflection, no kotlinx.serialization/Gson/Moshi.
# Vendor libraries (Firebase, Mapbox, Compose, coroutines, datastore) ship their
# own consumer ProGuard rules inside their AARs, applied automatically.
