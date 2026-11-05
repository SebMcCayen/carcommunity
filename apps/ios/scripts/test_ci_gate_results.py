import json
import os
from pathlib import Path
import subprocess
import sys
import unittest


SCRIPT = Path(__file__).with_name("ci-gate-results.py")
JOBS = (
    "ios-preflight", "ios-build-debug", "ios-community-ui", "ios-simulator",
    "ios-build-release", "ios-release", "ios-native", "ios-device",
)


class CIGateResultsTests(unittest.TestCase):
    def run_gate(self, results=None, *, raw=None):
        value = json.dumps(results) if raw is None else raw
        return subprocess.run(
            [sys.executable, str(SCRIPT)],
            env={**os.environ, "IOS_GATE_RESULTS": value},
            capture_output=True, text=True, check=False,
        )

    def successful_results(self):
        return {job: {"result": "success", "outputs": {}} for job in JOBS}

    def test_all_eight_successful_jobs_pass(self):
        self.assertEqual(self.run_gate(self.successful_results()).returncode, 0)

    def test_each_failed_cancelled_or_skipped_job_blocks_the_check(self):
        for job in JOBS:
            for state in ("failure", "cancelled", "skipped", "in_progress", None):
                with self.subTest(job=job, state=state):
                    results = self.successful_results()
                    results[job]["result"] = state
                    outcome = self.run_gate(results)
                    self.assertEqual(outcome.returncode, 1)
                    self.assertIn(job, outcome.stdout)

    def test_each_missing_job_blocks_the_check(self):
        for job in JOBS:
            with self.subTest(job=job):
                results = self.successful_results()
                del results[job]
                self.assertEqual(self.run_gate(results).returncode, 1)

    def test_invalid_and_unexpected_results_cannot_pass(self):
        unexpected = self.successful_results()
        unexpected["unexpected-job"] = {"result": "failure"}
        for results in (None, [], {}, unexpected, {job: "success" for job in JOBS}):
            with self.subTest(results=results):
                self.assertEqual(self.run_gate(results).returncode, 1)
        for raw in ("", "not-json"):
            with self.subTest(raw=raw):
                self.assertEqual(self.run_gate(raw=raw).returncode, 1)

    def test_job_outputs_are_not_written_to_the_log(self):
        results = self.successful_results()
        results["ios-native"] = {
            "result": "failure", "outputs": {"value": "private-output-marker"},
        }
        outcome = self.run_gate(results)
        self.assertEqual(outcome.returncode, 1)
        self.assertNotIn("private-output-marker", outcome.stdout + outcome.stderr)


if __name__ == "__main__":
    unittest.main()
