import contextlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock


SPEC = importlib.util.spec_from_file_location("export_test_results", Path(__file__).with_name("export-test-results.py"))
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
SECRET = "stderr credential marker must never appear"


class ExportTestResultsTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.results = self.root / "failed tests $(literal).xcresult"
        self.results.mkdir()
        self.output = self.root / "failure evidence"

    def run_export(self, effects):
        messages = io.StringIO()
        with mock.patch.object(MODULE.subprocess, "run", side_effect=effects) as run:
            with contextlib.redirect_stdout(messages), contextlib.redirect_stderr(messages):
                result = MODULE.main(["--results", str(self.results), "--output", str(self.output)])
        self.assertNotIn(SECRET, messages.getvalue())
        status = json.loads((self.output / "export-status.json").read_text())
        self.assertNotIn(SECRET, json.dumps(status))
        return result, run, status

    def successful_summary(self):
        return subprocess.CompletedProcess([], 0, json.dumps({
            "failedTests": 1, "testFailures": [{"testName": "testMenu", "message": "Förväntat värde"}],
        }), SECRET)

    def test_exports_original_attachment_bytes_and_structured_summary_with_literal_paths(self):
        original = b"\x89PNG\r\n\x1a\nactual attachment fixture bytes"

        def export_attachments(arguments, **kwargs):
            destination = Path(arguments[arguments.index("--output-path") + 1])
            destination.mkdir()
            (destination / "failure.png").write_bytes(original)
            return subprocess.CompletedProcess(arguments, 0)

        # A mock side effect dispatches the attachment command to the fake exporter.
        def dispatch(arguments, **kwargs):
            if "attachments" in arguments:
                return export_attachments(arguments, **kwargs)
            return self.successful_summary()

        result, run, status = self.run_export(dispatch)
        self.assertEqual(result, 0)
        self.assertEqual(status, {"schema": 1, "attachments": {"status": "success"}, "summary": {"status": "success"}})
        self.assertEqual((self.output / "attachments/failure.png").read_bytes(), original)
        self.assertEqual(json.loads((self.output / "summary.json").read_text()), json.loads(self.successful_summary().stdout))
        self.assertEqual(run.call_args_list, [
            mock.call(["xcrun", "xcresulttool", "export", "attachments", "--path", str(self.results),
                       "--output-path", str(self.output / "attachments")], check=True, stdout=subprocess.DEVNULL,
                      stderr=subprocess.PIPE, text=True, timeout=240),
            mock.call(["xcrun", "xcresulttool", "get", "test-results", "summary", "--path", str(self.results)],
                      check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=60),
        ])

    def test_missing_bundle_is_a_notice_without_subprocess_calls_or_output(self):
        self.results.rmdir()
        messages = io.StringIO()
        with mock.patch.object(MODULE.subprocess, "run") as run, contextlib.redirect_stdout(messages):
            result = MODULE.main(["--results", str(self.results), "--output", str(self.output)])
        self.assertEqual(result, 0)
        self.assertIn("No XCTest result bundle", messages.getvalue())
        run.assert_not_called()
        self.assertFalse(self.output.exists())

    def test_attachment_errors_and_timeouts_still_export_summary_without_leaking_errors(self):
        for failure, reason in (
            (subprocess.CalledProcessError(4, [SECRET], output=SECRET, stderr=SECRET), "command_failed"),
            (subprocess.TimeoutExpired([SECRET], 240, output=SECRET, stderr=SECRET), "timeout"),
            (OSError(SECRET), "io_error"),
        ):
            with self.subTest(reason=reason):
                result, run, status = self.run_export([failure, self.successful_summary()])
                self.assertEqual(result, 1)
                self.assertEqual(run.call_count, 2)
                self.assertEqual(status["attachments"]["reason"], reason)
                self.assertEqual(status["summary"]["status"], "success")
                self.assertEqual(json.loads((self.output / "summary.json").read_text())["failedTests"], 1)

    def test_summary_errors_timeouts_and_invalid_json_preserve_attachment_status(self):
        for failure, reason in (
            (subprocess.CalledProcessError(5, [SECRET], output=SECRET, stderr=SECRET), "command_failed"),
            (subprocess.TimeoutExpired([SECRET], 60, output=SECRET, stderr=SECRET), "timeout"),
            (subprocess.CompletedProcess([], 0, SECRET, SECRET), "invalid_summary"),
            (subprocess.CompletedProcess([], 0, "[]", SECRET), "invalid_summary"),
        ):
            with self.subTest(reason=reason):
                result, run, status = self.run_export([subprocess.CompletedProcess([], 0), failure])
                self.assertEqual(result, 1)
                self.assertEqual(run.call_count, 2)
                self.assertEqual(status["attachments"]["status"], "success")
                self.assertEqual(status["summary"]["reason"], reason)
                self.assertFalse((self.output / "summary.json").exists())


if __name__ == "__main__":
    unittest.main()
