#!/usr/bin/env python3
"""Install an APK on remote Android device once media playback stops.

Monitors the media session state of com.nuvio.tv.plus on 192.168.1.242:5555.
While the app reports PlaybackState.STATE_PLAYING (state=3), keeps waiting.
As soon as playback stops, installs the APK with adb install -r.
"""

from __future__ import annotations

import subprocess
import sys
import time
from pathlib import Path

sys.stdout.reconfigure(line_buffering=True)

DEVICE_SERIAL: str = "192.168.1.242:5555"
RELEASE_TAG: str = "0.9.4-plus.82"
RELEASE_DIR: Path = Path("c:/Users/mboud/Nuvio_Skills/NuvioTV/release-apk")
APK_PATH: Path = RELEASE_DIR / "app-full-arm64-v8a-release.apk"
TARGET_PACKAGE: str = "com.nuvio.tv.plus"

POLL_INTERVAL_SECONDS: int = 10
RELEASE_WAIT_TIMEOUT_SECONDS: int = 35 * 60  # 35 minutes
POLL_RELEASE_SECONDS: int = 15


def run_adb(args: list[str], *, timeout: int = 60) -> subprocess.CompletedProcess[str]:
    subprocess.run(["adb", "connect", DEVICE_SERIAL], capture_output=True, timeout=10)
    return subprocess.run(
        ["adb", "-s", DEVICE_SERIAL, *args],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=timeout,
        check=False,
    )


def wait_and_download_release(tag: str, dest_dir: Path, timeout: int) -> Path | None:
    dest_dir.mkdir(parents=True, exist_ok=True)
    target_apk = dest_dir / "app-full-arm64-v8a-release.apk"
    deadline = time.monotonic() + timeout

    print(f"[release] Waiting for GitHub release '{tag}' to be published...")
    while time.monotonic() < deadline:
        # Check if release is available
        check = subprocess.run(
            ["gh", "release", "view", tag, "-R", "hindsight2021/NuvioTV"],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=30,
            check=False,
        )
        if check.returncode == 0:
            print(f"[release] Release '{tag}' found! Downloading APK asset...")
            dl = subprocess.run(
                ["gh", "release", "download", tag, "-p", "*.apk", "-D", str(dest_dir), "--clobber", "-R", "hindsight2021/NuvioTV"],
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=120,
                check=False,
            )
            if dl.returncode == 0 and target_apk.is_file() and target_apk.stat().st_size > 10_000_000:
                print(f"[release] Successfully downloaded: {target_apk} ({target_apk.stat().st_size} bytes)")
                return target_apk
            print(f"[release] Download returned {dl.returncode}: {dl.stderr.strip() or dl.stdout.strip()}")

        remaining = int(deadline - time.monotonic())
        print(f"[wait] Release '{tag}' not ready yet, retrying... ({remaining}s remaining)")
        time.sleep(POLL_RELEASE_SECONDS)

    return None


def is_playing() -> bool:
    try:
        result = run_adb(["shell", "dumpsys", "media_session"])
    except Exception as exc:
        print(f"[warn] adb dumpsys failed: {exc}")
        return True  # If error, err on the side of caution (don't interrupt)

    if result.returncode != 0:
        print(f"[warn] adb returned {result.returncode}: {result.stderr.strip()}")
        return True

    lines = result.stdout.splitlines()
    in_target_session = False
    for line in lines:
        line_s = line.strip()
        if "package=" in line_s:
            in_target_session = (TARGET_PACKAGE in line_s)
        elif in_target_session:
            if "state=PlaybackState" in line_s:
                if "state=3" in line_s or "state=PLAYING" in line_s:
                    return True
                else:
                    return False
            elif "SessionRecord" in line_s or "2) MediaButtonSession" in line_s:
                in_target_session = False

    return False


def install_apk(path: Path) -> bool:
    print(f"[install] Installing {path} on {DEVICE_SERIAL} ...")
    try:
        result = run_adb(["install", "-r", str(path)], timeout=300)
    except Exception as exc:
        print(f"[error] adb install failed: {exc}")
        return False

    output = (result.stdout + result.stderr).strip()
    if result.returncode == 0 and "Success" in result.stdout:
        print(f"[install] Success: {output}")
        return True

    print(f"[error] Install failed (rc={result.returncode}): {output}")
    return False


def main() -> int:
    apk_file = wait_and_download_release(RELEASE_TAG, RELEASE_DIR, RELEASE_WAIT_TIMEOUT_SECONDS)
    if apk_file is None:
        print("[error] Release APK did not become available. Aborting.")
        return 1

    print(f"[monitor] Watching playback state of {TARGET_PACKAGE} on {DEVICE_SERIAL} ...")
    while True:
        if is_playing():
            print("[monitor] Video playback is currently ACTIVE in Nuvio. Waiting 10s...")
            time.sleep(POLL_INTERVAL_SECONDS)
            continue

        print("[monitor] Playback is NO LONGER ACTIVE! Proceeding with update install...")
        break

    if install_apk(apk_file):
        print(f"[done] Release {RELEASE_TAG} installed successfully!")
        time.sleep(2)
        print("[launch] Relaunching Nuvio TV...")
        run_adb(["shell", "monkey", "-p", TARGET_PACKAGE, "-c", "android.intent.category.LAUNCHER", "1"])
        time.sleep(5)
        print("[screenshot] Taking screenshot of the home screen...")
        try:
            screen_proc = subprocess.run(
                ["adb", "-s", DEVICE_SERIAL, "exec-out", "screencap", "-p"],
                capture_output=True,
                timeout=30,
                check=False
            )
            if screen_proc.returncode == 0 and screen_proc.stdout:
                with open("screen_now.png", "wb") as f:
                    f.write(screen_proc.stdout)
                print("[screenshot] Saved to screen_now.png")
        except Exception as exc:
            print(f"[warn] Screenshot failed: {exc}")
        return 0
    return 1


if __name__ == "__main__":
    sys.exit(main())
