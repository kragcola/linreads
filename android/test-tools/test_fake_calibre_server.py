import json
import socket
import subprocess
import sys
import time
import unittest
import urllib.request

from pathlib import Path


class FakeCalibreServerTest(unittest.TestCase):
    def test_cover_route_returns_png_and_records_cover_event(self):
        port = self._free_port()
        server = subprocess.Popen(
            [
                sys.executable,
                str(Path(__file__).with_name("fake_calibre_server.py")),
                "--host",
                "127.0.0.1",
                "--port",
                str(port),
            ],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
        )
        try:
            self._wait_for_server(port)

            with urllib.request.urlopen(
                f"http://127.0.0.1:{port}/get/cover/42/calibre-library",
                timeout=2,
            ) as response:
                body = response.read()
                content_type = response.headers["Content-Type"]

            with urllib.request.urlopen(
                f"http://127.0.0.1:{port}/__events__",
                timeout=2,
            ) as response:
                events = json.loads(response.read().decode("utf-8"))["events"]

            self.assertEqual("image/png", content_type)
            self.assertTrue(body.startswith(b"\x89PNG\r\n\x1a\n"))
            self.assertEqual(
                [
                    {
                        "index": 1,
                        "kind": "cover",
                        "path": "/get/cover/42/calibre-library",
                        "book_id": 42,
                    }
                ],
                events,
            )
        finally:
            self._shutdown_server(port)
            server.wait(timeout=5)
            if server.stdout is not None:
                server.stdout.close()

    @staticmethod
    def _free_port() -> int:
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            return int(sock.getsockname()[1])

    @staticmethod
    def _wait_for_server(port: int) -> None:
        deadline = time.time() + 5
        while time.time() < deadline:
            try:
                urllib.request.urlopen(f"http://127.0.0.1:{port}/__events__", timeout=0.2)
                return
            except OSError:
                time.sleep(0.05)
        raise AssertionError("fake Calibre server did not start")

    @staticmethod
    def _shutdown_server(port: int) -> None:
        try:
            urllib.request.urlopen(f"http://127.0.0.1:{port}/__shutdown__", timeout=2).read()
        except OSError:
            pass


if __name__ == "__main__":
    unittest.main()
