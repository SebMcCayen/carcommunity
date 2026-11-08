import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch
from urllib.request import Request

import github_release_asset as transport

spec = importlib.util.spec_from_file_location("gradle_cache", Path(__file__).with_name("prepare-gradle-cache.py"))
gradle = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gradle)


class ReleaseTransportTests(unittest.TestCase):
    def test_ci_token_is_only_an_unredirected_api_header(self):
        request = Request("https://api.github.com/repos/example/repo/releases/assets/1")
        opener = Mock()
        with patch.dict(transport.os.environ, {"GITHUB_TOKEN": "synthetic-test-token"}), patch.object(transport, "build_opener", return_value=opener):
            transport.open_release_asset(request, timeout=60)
        self.assertNotIn("Authorization", request.headers)
        self.assertEqual(request.unredirected_hdrs["Authorization"], "Bearer synthetic-test-token")
        opener.open.assert_called_once_with(request, timeout=60)

    def test_cdn_redirect_drops_both_normal_and_unredirected_authentication(self):
        request = Request("https://api.github.com/repos/example/repo/releases/assets/1", headers={"Accept": "application/octet-stream", "Authorization": "synthetic-normal-token"})
        request.add_unredirected_header("Authorization", "synthetic-unredirected-token")
        redirected = transport.PublicAssetRedirect().redirect_request(request, None, 302, "Found", {}, "https://release-assets.githubusercontent.com/example")
        self.assertIsNone(redirected.get_header("Authorization"))
        self.assertEqual(redirected.get_header("Accept"), "application/octet-stream")

    def test_http_downgrade_is_refused(self):
        with self.assertRaisesRegex(ValueError, "HTTPS"):
            transport.PublicAssetRedirect().redirect_request(Request("https://api.github.com/a"), None, 302, "Found", {}, "http://example.com/asset")

    def test_authentication_to_non_api_host_is_refused_before_transport(self):
        with patch.object(transport, "build_opener", side_effect=AssertionError("transport invoked")):
            for url in ["https://example.com/asset", "http://api.github.com/asset", "https://api.github.com.example/asset"]:
                with self.subTest(url=url), self.assertRaises(ValueError):
                    transport.open_release_asset(Request(url), timeout=60)


class GradleCacheTests(unittest.TestCase):
    def test_current_committed_pin_and_wrapper_path(self):
        gradle.verify_pin(Path(__file__).resolve().parents[1] / "apps/android/gradle/wrapper/gradle-wrapper.properties")
        # Independent reference computed using Gradle's unsigned MD5/base36 algorithm.
        self.assertEqual(gradle.cache_key(gradle.URL), "23ovyewtku6u96viwx3xl3oks")

    def test_version_checksum_and_layout_changes_require_review(self):
        original = (Path(__file__).resolve().parents[1] / "apps/android/gradle/wrapper/gradle-wrapper.properties").read_text()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "gradle-wrapper.properties"
            for before, after in [("9.3.1", "9.4.0"), (gradle.SHA256, "0" * 64), ("wrapper/dists", "different")]:
                path.write_text(original.replace(before, after))
                with self.subTest(change=before), self.assertRaises(ValueError):
                    gradle.verify_pin(path)


if __name__ == "__main__":
    unittest.main()
