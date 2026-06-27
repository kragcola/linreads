import json
import subprocess
import sys
import tempfile
import unittest
import zipfile

from pathlib import Path


class ExtremeReadingCorpusTest(unittest.TestCase):
    def test_generator_writes_shared_corpus_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            out_dir = Path(tmp)
            result = subprocess.run(
                [
                    sys.executable,
                    str(Path(__file__).with_name("extreme_reading_corpus.py")),
                    "--out",
                    str(out_dir),
                ],
                check=True,
                capture_output=True,
                text=True,
            )
            payload = json.loads(result.stdout)
            self.assertEqual(str(out_dir.resolve()), payload["out"])

            epub = out_dir / "rf-mr-extreme-20260627.epub"
            txt = out_dir / "rf-mr-extreme-20260627-utf8.txt"
            pdf = out_dir / "rf-mr-extreme-20260627.pdf"
            manifest = out_dir / "manifest.json"

            self.assertTrue(epub.exists())
            self.assertTrue(txt.exists())
            self.assertTrue(pdf.exists())
            self.assertTrue(manifest.exists())

            with zipfile.ZipFile(epub) as archive:
                names = archive.namelist()
                self.assertEqual("mimetype", names[0])
                self.assertIn("OEBPS/content.opf", names)
                self.assertIn("OEBPS/chapter-1.xhtml", names)
                chapter = archive.read("OEBPS/chapter-1.xhtml").decode("utf-8")
                self.assertIn("RFMR-CJK-001", chapter)

            txt_body = txt.read_text(encoding="utf-8")
            self.assertIn("RFMR-TXT-000001", txt_body)
            self.assertIn("RFMR-TXT-NEAR-TAIL", txt_body)

            pdf_body = pdf.read_bytes()
            self.assertTrue(pdf_body.startswith(b"%PDF-1.4"))
            self.assertIn(b"RFMR PDF PAGE 001", pdf_body)
            self.assertTrue(pdf_body.rstrip().endswith(b"%%EOF"))

            manifest_payload = json.loads(manifest.read_text(encoding="utf-8"))
            self.assertEqual("rf-mr-extreme-20260627", manifest_payload["corpus_id"])
            self.assertEqual(3, len(manifest_payload["files"]))


if __name__ == "__main__":
    unittest.main()
