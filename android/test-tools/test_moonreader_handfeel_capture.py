import json
import subprocess
import sys
import tempfile
import unittest

from pathlib import Path


class MoonReaderHandfeelCaptureTest(unittest.TestCase):
    def test_dry_run_writes_protocol_manifest_without_adb(self):
        with tempfile.TemporaryDirectory() as tmp:
            out_dir = Path(tmp)
            result = subprocess.run(
                [
                    sys.executable,
                    str(Path(__file__).with_name("moonreader_handfeel_capture.py")),
                    "--out",
                    str(out_dir),
                    "--device-label",
                    "phone-portrait",
                    "--gate",
                    "phone-handfeel",
                    "--book",
                    "rf-mr-extreme-20260627.epub",
                    "--dry-run",
                ],
                check=True,
                capture_output=True,
                text=True,
            )

            payload = json.loads(result.stdout)
            run_dir = Path(payload["run_dir"])
            self.assertTrue(run_dir.exists())
            self.assertEqual("dry_run", payload["status"])

            manifest = json.loads((run_dir / "manifest.json").read_text(encoding="utf-8"))
            self.assertEqual("phone-portrait", manifest["device_label"])
            self.assertEqual("phone-handfeel", manifest["gate"])
            self.assertEqual("rf-mr-extreme-20260627.epub", manifest["book"])
            self.assertTrue(manifest["dry_run"])
            self.assertIn("edge swipe", manifest["manual_matrix"])
            self.assertIn("inner-center temporary scroll", manifest["manual_matrix"])
            self.assertIn("release-time no-snap A/B", manifest["protocol_gates"])

            notes = (run_dir / "NOTES.md").read_text(encoding="utf-8")
            self.assertIn("Device", notes)
            self.assertIn("Decision", notes)

    def test_preset_fills_common_phone_gate_defaults(self):
        with tempfile.TemporaryDirectory() as tmp:
            out_dir = Path(tmp)
            result = subprocess.run(
                [
                    sys.executable,
                    str(Path(__file__).with_name("moonreader_handfeel_capture.py")),
                    "--out",
                    str(out_dir),
                    "--preset",
                    "phone",
                    "--book",
                    "rf-mr-extreme-20260627.epub",
                    "--dry-run",
                ],
                check=True,
                capture_output=True,
                text=True,
            )

            payload = json.loads(result.stdout)
            run_dir = Path(payload["run_dir"])
            manifest = json.loads((run_dir / "manifest.json").read_text(encoding="utf-8"))

            self.assertEqual("phone", manifest["preset"])
            self.assertEqual("phone-portrait", manifest["device_label"])
            self.assertEqual("phone-handfeel", manifest["gate"])
            self.assertEqual(30, manifest["record_seconds"])
            self.assertEqual("phone hand feel", manifest["preset_focus"])
            self.assertIn("edge swipe", manifest["manual_matrix"])


if __name__ == "__main__":
    unittest.main()
