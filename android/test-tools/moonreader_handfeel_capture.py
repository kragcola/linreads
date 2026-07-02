#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import time
from pathlib import Path


CRITICAL_LOGCAT_PATTERN = (
    r"FATAL EXCEPTION|ANR in dev\.readflow|OutOfMemoryError|recycled bitmap|"
    r"Unable to find reader surface|AssertionError|FAILURES!!!|Process: dev\.readflow"
)

MANUAL_MATRIX = [
    "edge swipe",
    "edge tap",
    "center tap",
    "inner-center temporary scroll",
    "center-ring drag",
    "long-press selection",
    "inline link tap",
    "rapid post-settle tap/fling",
]

PROTOCOL_GATES = [
    "phone hand feel",
    "tablet hand feel",
    "release-time no-snap A/B",
    "post-settle rapid input",
    "image-heavy EPUB GL cache",
    "tap layout customization",
]

PRESETS = {
    "phone": {
        "device_label": "phone-portrait",
        "gate": "phone-handfeel",
        "record_seconds": 30,
        "preset_focus": "phone hand feel",
    },
    "tablet": {
        "device_label": "tablet-landscape",
        "gate": "tablet-handfeel",
        "record_seconds": 45,
        "preset_focus": "tablet hand feel",
    },
    "ab": {
        "device_label": "phone-portrait",
        "gate": "release-time-no-snap-ab",
        "record_seconds": 30,
        "preset_focus": "release-time no-snap A/B",
    },
}


def main() -> None:
    parser = argparse.ArgumentParser(
        description=(
            "Collect a physical-device evidence bundle for MoonReader-inspired "
            "LinReads EPUB/PAGED hand-feel decisions."
        ),
    )
    parser.add_argument(
        "--out",
        default="/tmp/readflow-moonreader-handfeel",
        help="Evidence root directory. Defaults to /tmp/readflow-moonreader-handfeel.",
    )
    parser.add_argument("--serial", help="adb device serial. Defaults to the active adb device.")
    parser.add_argument(
        "--adb",
        default="adb",
        help="adb executable path. Defaults to adb from PATH.",
    )
    parser.add_argument(
        "--device-label",
        default=None,
        help="Short label, e.g. phone-portrait or tablet-landscape.",
    )
    parser.add_argument(
        "--gate",
        default=None,
        help="Protocol gate being captured, e.g. phone-handfeel or no-snap-ab.",
    )
    parser.add_argument(
        "--book",
        required=True,
        help="Book/corpus filename or description used during the manual run.",
    )
    parser.add_argument(
        "--record-seconds",
        type=int,
        default=30,
        help="Screen-record duration in seconds. Defaults to 30.",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Create manifest/notes only; do not call adb.",
    )
    parser.add_argument(
        "--preset",
        choices=sorted(PRESETS),
        help="Optional preset for common gates: phone, tablet, or ab.",
    )
    args = parser.parse_args()

    preset = PRESETS.get(args.preset, {})
    if args.preset:
        if args.device_label is None:
            args.device_label = preset["device_label"]
        if args.gate is None:
            args.gate = preset["gate"]
        if args.record_seconds == 30:
            args.record_seconds = preset["record_seconds"]
        args.preset_focus = preset["preset_focus"]
    else:
        args.preset_focus = None

    if args.device_label is None or args.gate is None:
        parser.error("--device-label and --gate are required unless --preset is provided")

    run_dir = create_run_dir(Path(args.out), args.device_label, args.gate)
    manifest = base_manifest(args, run_dir)
    write_manifest(run_dir, manifest)
    write_notes(run_dir, manifest)

    if args.dry_run:
        print(json.dumps({"status": "dry_run", "run_dir": str(run_dir)}, ensure_ascii=False))
        return

    adb = resolve_adb(args.adb)
    serial_args = ["-s", args.serial] if args.serial else []
    manifest["adb"] = str(adb)
    manifest["device"] = collect_device_info(adb, serial_args)
    write_text(run_dir / "adb-devices.txt", run_adb(adb, [], ["devices", "-l"], check=False))

    capture_screenshot(adb, serial_args, run_dir / "screenshot-before.png")
    record_screen(adb, serial_args, args.record_seconds, run_dir / "screenrecord.mp4")
    capture_screenshot(adb, serial_args, run_dir / "screenshot-after.png")
    write_text(run_dir / "logcat-critical.txt", collect_critical_logcat(adb, serial_args))

    manifest["captured_files"] = sorted(path.name for path in run_dir.iterdir() if path.is_file())
    write_manifest(run_dir, manifest)
    write_notes(run_dir, manifest)
    print(json.dumps({"status": "captured", "run_dir": str(run_dir)}, ensure_ascii=False))


def create_run_dir(root: Path, device_label: str, gate: str) -> Path:
    stamp = time.strftime("%Y%m%d-%H%M%S")
    safe_label = sanitize(device_label)
    safe_gate = sanitize(gate)
    run_dir = root.expanduser().resolve() / f"{stamp}-{safe_label}-{safe_gate}"
    run_dir.mkdir(parents=True, exist_ok=False)
    return run_dir


def base_manifest(args: argparse.Namespace, run_dir: Path) -> dict:
    return {
        "created_at_epoch": int(time.time()),
        "run_dir": str(run_dir),
        "device_label": args.device_label,
        "gate": args.gate,
        "book": args.book,
        "record_seconds": args.record_seconds,
        "dry_run": bool(args.dry_run),
        "preset": args.preset,
        "preset_focus": getattr(args, "preset_focus", None),
        "manual_matrix": MANUAL_MATRIX,
        "protocol_gates": PROTOCOL_GATES,
        "critical_logcat_pattern": CRITICAL_LOGCAT_PATTERN,
        "decision": "fill: keep current / run another A/B / implement narrow change",
    }


def resolve_adb(adb_arg: str) -> Path:
    candidate = Path(adb_arg).expanduser()
    if candidate.exists():
        return candidate.resolve()
    found = shutil.which(adb_arg)
    if found:
        return Path(found).resolve()
    raise SystemExit(f"adb executable not found: {adb_arg}")


def collect_device_info(adb: Path, serial_args: list[str]) -> dict:
    props = {}
    for key in [
        "ro.product.manufacturer",
        "ro.product.model",
        "ro.build.version.release",
        "ro.build.version.sdk",
        "ro.sf.lcd_density",
    ]:
        props[key] = run_adb(adb, serial_args, ["shell", "getprop", key], check=False).strip()
    wm_size = run_adb(adb, serial_args, ["shell", "wm", "size"], check=False).strip()
    wm_density = run_adb(adb, serial_args, ["shell", "wm", "density"], check=False).strip()
    return {"props": props, "wm_size": wm_size, "wm_density": wm_density}


def capture_screenshot(adb: Path, serial_args: list[str], dest: Path) -> None:
    data = run_adb_bytes(adb, serial_args, ["exec-out", "screencap", "-p"])
    dest.write_bytes(data)


def record_screen(adb: Path, serial_args: list[str], seconds: int, dest: Path) -> None:
    remote = "/sdcard/readflow-handfeel-screenrecord.mp4"
    run_adb(
        adb,
        serial_args,
        ["shell", "screenrecord", "--time-limit", str(seconds), remote],
        check=False,
    )
    run_adb(adb, serial_args, ["pull", remote, str(dest)], check=True)
    run_adb(adb, serial_args, ["shell", "rm", "-f", remote], check=False)


def collect_critical_logcat(adb: Path, serial_args: list[str]) -> str:
    logcat = run_adb(adb, serial_args, ["logcat", "-d", "-t", "5000"], check=False)
    matches = [line for line in logcat.splitlines() if critical_line(line)]
    return "\n".join(matches) + ("\n" if matches else "")


def critical_line(line: str) -> bool:
    needles = [
        "FATAL EXCEPTION",
        "ANR in dev.readflow",
        "OutOfMemoryError",
        "recycled bitmap",
        "Unable to find reader surface",
        "AssertionError",
        "FAILURES!!!",
        "Process: dev.readflow",
    ]
    return any(needle in line for needle in needles)


def run_adb(adb: Path, serial_args: list[str], args: list[str], check: bool) -> str:
    result = subprocess.run(
        [str(adb), *serial_args, *args],
        check=check,
        capture_output=True,
        text=True,
    )
    return result.stdout + result.stderr


def run_adb_bytes(adb: Path, serial_args: list[str], args: list[str]) -> bytes:
    result = subprocess.run(
        [str(adb), *serial_args, *args],
        check=True,
        capture_output=True,
    )
    return result.stdout


def write_manifest(run_dir: Path, manifest: dict) -> None:
    (run_dir / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def write_notes(run_dir: Path, manifest: dict) -> None:
    lines = [
        "# MoonReader Handfeel Evidence Notes",
        "",
        f"Device: {manifest['device_label']}",
        f"Gate: {manifest['gate']}",
        f"Book: {manifest['book']}",
        "",
        "## Manual Matrix",
        "",
        *[f"- [ ] {item}" for item in MANUAL_MATRIX],
        "",
        "## Observations",
        "",
        "- Accidental flips:",
        "- Link/selection/chrome ownership:",
        "- GL follow/cancel/commit feel:",
        "- Half-line or cross-page seam:",
        "- Crash/OOM/logcat signatures:",
        "",
        "## Decision",
        "",
        "keep current / run another A/B / implement narrow change",
        "",
    ]
    (run_dir / "NOTES.md").write_text("\n".join(lines), encoding="utf-8")


def write_text(path: Path, body: str) -> None:
    path.write_text(body, encoding="utf-8")


def sanitize(value: str) -> str:
    return "".join(ch if ch.isalnum() or ch in "-_" else "-" for ch in value).strip("-") or "run"


if __name__ == "__main__":
    main()
