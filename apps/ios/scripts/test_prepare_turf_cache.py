import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import HTTPError

spec = importlib.util.spec_from_file_location("prepare_turf_cache", Path(__file__).with_name("prepare-turf-cache.py"))
MODULE = importlib.util.module_from_spec(spec)
spec.loader.exec_module(MODULE)


class TurfCacheTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.payload = b"synthetic SDK archive for integrity tests"
        self.size = patch.object(MODULE, "EXPECTED_SIZE", len(self.payload))
        self.digest = patch.object(MODULE, "EXPECTED_SHA256", hashlib.sha256(self.payload).hexdigest())
        self.size.start(); self.digest.start()
        self.addCleanup(self.size.stop); self.addCleanup(self.digest.stop)

    def opener(self, request, timeout):
        self.assertEqual(request.full_url, MODULE.API_URL)
        self.assertEqual(request.get_header("Accept"), "application/octet-stream")
        self.assertIsNone(request.get_header("Authorization"))
        return io.BytesIO(self.payload)

    def test_verified_public_asset_is_cached_atomically_without_credentials(self):
        target = MODULE.prepare(self.root, opener=self.opener)
        self.assertEqual(target.read_bytes(), self.payload)
        self.assertEqual(list(self.root.iterdir()), [target])

    def test_valid_cache_hit_does_not_contact_the_network(self):
        target = self.root / MODULE.CACHE_NAME
        target.write_bytes(self.payload)
        self.assertEqual(MODULE.prepare(self.root, opener=lambda *_args, **_kwargs: self.fail("network on cache hit")), target)

    def test_checksum_failure_does_not_replace_existing_cache_or_leave_partial_downloads(self):
        target = self.root / MODULE.CACHE_NAME
        target.write_bytes(b"existing corrupt cache")
        with self.assertRaisesRegex(ValueError, "checksum"):
            MODULE.prepare(self.root, opener=lambda *_args, **_kwargs: io.BytesIO(b"x" * len(self.payload)))
        self.assertEqual(target.read_bytes(), b"existing corrupt cache")
        self.assertEqual(list(self.root.iterdir()), [target])

    def test_oversized_response_is_rejected_and_temporary_file_removed(self):
        with self.assertRaisesRegex(ValueError, "size"):
            MODULE.prepare(self.root, opener=lambda *_args, **_kwargs: io.BytesIO(self.payload + b"x"))
        self.assertEqual(list(self.root.iterdir()), [])

    def test_gateway_error_retries_then_verifies_the_payload(self):
        attempts = []
        def opener(request, timeout):
            attempts.append(request.full_url)
            if len(attempts) == 1:
                raise HTTPError(MODULE.API_URL, 504, "Gateway Timeout", None, None)
            return self.opener(request, timeout)
        pauses = []
        self.assertTrue(MODULE.valid_archive(MODULE.prepare(self.root, opener=opener, sleep=pauses.append)))
        self.assertEqual(len(attempts), 2)
        self.assertEqual(pauses, [5])

    def test_missing_asset_is_not_retried(self):
        def opener(*_args, **_kwargs):
            raise HTTPError(MODULE.API_URL, 404, "Not Found", None, None)
        with self.assertRaises(HTTPError):
            MODULE.prepare(self.root, opener=opener, sleep=lambda _: self.fail("retrying a permanent error"))
        self.assertEqual(list(self.root.iterdir()), [])

    def test_repeated_gateway_errors_have_a_hard_retry_limit(self):
        attempts = []
        def opener(*_args, **_kwargs):
            attempts.append(1)
            raise HTTPError(MODULE.API_URL, 504, "Gateway Timeout", None, None)
        pauses = []
        with self.assertRaises(HTTPError):
            MODULE.prepare(self.root, opener=opener, sleep=pauses.append)
        self.assertEqual(len(attempts), 3)
        self.assertEqual(pauses, [5, 10])
        self.assertEqual(list(self.root.iterdir()), [])

    def test_cache_symlink_is_refused_without_reading_or_replacing_its_target(self):
        outside = self.root / "outside"
        outside.write_bytes(self.payload)
        cache = self.root / MODULE.CACHE_NAME
        cache.symlink_to(outside)
        with self.assertRaisesRegex(ValueError, "symlink"):
            MODULE.prepare(self.root, opener=lambda *_args, **_kwargs: self.fail("network for a symlink"))
        self.assertTrue(cache.is_symlink())
        self.assertEqual(outside.read_bytes(), self.payload)

    def test_dependency_version_change_requires_review(self):
        lock = self.root / "Package.resolved"
        lock.write_text(json.dumps({"pins": [{"identity": "turf-swift", "state": {"version": "4.0.0"}}]}))
        MODULE.verify_pin(lock)
        lock.write_text(json.dumps({"pins": [{"identity": "turf-swift", "state": {"version": "4.1.0"}}]}))
        with self.assertRaises(ValueError):
            MODULE.verify_pin(lock)


if __name__ == "__main__":
    unittest.main()
