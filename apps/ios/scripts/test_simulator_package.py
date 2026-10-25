"""Regression tests for the built-package checks, including both simulator architectures."""
import importlib.util
from pathlib import Path
import plistlib
import struct
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("simulator_package", Path(__file__).with_name("simulator-package.py"))
package = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(package)
BUNDLE = "com.kungsbackacarcommunity.app.local"


def thin(identity=BUNDLE, platform=7):
    payload = plistlib.dumps({"application-identifier": identity})
    offset = 32 + 24 + 152
    header = struct.pack("<IiiIIIII", 0xfeedfacf, 0x1000007, 3, 2, 2, offset - 32, 0, 0)
    build = struct.pack("<IIIIII", 0x32, 24, platform, 0, 0, 0)
    segment = struct.pack("<II16sQQQQiiII", 0x19, 152, b"__TEXT", 0, 0, 0, offset + len(payload), 7, 5, 1, 0)
    section = struct.pack("<16s16sQQIIIIIIII", b"__entitlements", b"__TEXT", 0, len(payload), offset, 0, 0, 0, 0, 0, 0, 0)
    return header + build + segment + section + payload


def universal(slices, wide=False):
    width = 32 if wide else 20
    offset = 8 + width * len(slices)
    table = b""
    for item in slices:
        table += struct.pack(">IIQQII" if wide else ">IIIII", 0x1000007, 3, offset, len(item), 0, *([0] if wide else []))
        offset += len(item)
    return struct.pack(">II", 0xcafebabf if wide else 0xcafebabe, len(slices)) + table + b"".join(slices)


class SimulatorPackageTests(unittest.TestCase):
    def test_reads_thin_and_both_universal_header_formats(self):
        for image in (thin(), universal([thin(), thin()]), universal([thin(), thin()], wide=True)):
            self.assertTrue(all(value == BUNDLE for value in package.simulator_identities(image)))

    def test_rejects_missing_entitlements_device_binary_and_malformed_tables(self):
        mutations = [b"", b"not-mach-o", thin(platform=2), thin()[:30], universal([thin()])[:-1]]
        missing = bytearray(thin())
        missing[32 + 24 + 72:32 + 24 + 72 + 16] = b"__other".ljust(16, b"\0")
        mutations.append(bytes(missing))
        malformed = bytearray(thin())
        struct.pack_into("<I", malformed, 32 + 24 + 72 + 48, 0xffffffff)
        mutations.append(bytes(malformed))
        for image in mutations:
            with self.subTest(length=len(image)), self.assertRaises(package.PackageError):
                package.simulator_identities(image)

    def test_one_invalid_universal_slice_fails_the_entire_package(self):
        with self.assertRaises(package.PackageError):
            package.simulator_identities(universal([thin(), thin(platform=2)]))

    def test_demo_configuration_and_identity_are_both_required(self):
        with tempfile.TemporaryDirectory() as directory:
            app = Path(directory)
            (app / "Info.plist").write_bytes(plistlib.dumps({"CFBundleIdentifier": BUNDLE, "CFBundleExecutable": "KCC"}))
            (app / "KCC").write_bytes(thin())
            self.assertEqual(package.check_package(app), 1)
            config = {"BUNDLE_ID": BUNDLE, "PROJECT_ID": "demo-kcc-ios", "API_KEY": "AIza" + "0" * 35}
            path = app / "GoogleService-Info.plist"
            path.write_bytes(plistlib.dumps(config))
            with self.assertRaisesRegex(package.PackageError, "Config-less"):
                package.check_package(app)
            self.assertEqual(package.check_package(app, "demo"), 1)
            for field, value in (("PROJECT_ID", "production-project"), ("API_KEY", "private-value"),
                                 ("BUNDLE_ID", "other"), ("DATABASE_URL", "https://real-project.firebaseio.com"),
                                 ("STORAGE_BUCKET", "real-project.appspot.com")):
                path.write_bytes(plistlib.dumps({**config, field: value}))
                with self.assertRaises(package.PackageError) as caught:
                    package.check_package(app, "demo")
                self.assertNotIn(value, str(caught.exception))
            path.write_bytes(plistlib.dumps(config))
            (app / "KCC").write_bytes(universal([thin(), thin(identity="wrong.identity")]))
            with self.assertRaisesRegex(package.PackageError, "does not match"):
                package.check_package(app, "demo")

    def test_executable_path_cannot_escape_bundle(self):
        with tempfile.TemporaryDirectory() as directory:
            app = Path(directory)
            (app / "Info.plist").write_bytes(plistlib.dumps({"CFBundleIdentifier": BUNDLE, "CFBundleExecutable": "../outside"}))
            with self.assertRaisesRegex(package.PackageError, "executable name"):
                package.check_package(app)

    def test_malformed_package_xml_is_reported_without_its_values(self):
        with tempfile.TemporaryDirectory() as directory:
            app = Path(directory)
            (app / "Info.plist").write_text('<?xml version="1.0"?><plist><dict><key>private-marker</key>')
            with self.assertRaises(package.PackageError) as caught:
                package.check_package(app)
            self.assertNotIn("private-marker", str(caught.exception))


if __name__ == "__main__":
    unittest.main()
