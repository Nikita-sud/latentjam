#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Run real Kotlin/Native tests, including Foundation I/O, on an available arm64 iPhone."""
import json
import platform
import subprocess

def run(*args):
    return subprocess.check_output(args, text=True)

def main():
    if platform.machine() != "arm64":
        raise SystemExit("Use an arm64 macOS runner; the project does not target Intel simulators.")
    devices = json.loads(run("xcrun", "simctl", "list", "devices", "available", "-j"))
    candidates = [d for runtime, items in devices["devices"].items() if "iOS" in runtime
                  for d in items if d["state"] == "Booted" or d["name"].startswith("iPhone")]
    if not candidates:
        raise SystemExit("An installed iOS simulator runtime is required.")
    device = next((d for d in candidates if d["state"] == "Booted"), candidates[0])
    if device["state"] != "Booted":
        subprocess.run(["xcrun", "simctl", "boot", device["udid"]], check=True)
    subprocess.run(["xcrun", "simctl", "bootstatus", device["udid"], "-b"], check=True)
    modules = ["core:smart", "core:library", "core:playback", "core:history", "composeApp"]
    subprocess.run(["./gradlew", "--max-workers=1",
                    "-Pkotlin.native.jvmArgs=-Xmx4g",
                    "-I", "tools/ci/ios-tests.init.gradle",
                    "-Platentjam.simulatorId=" + device["udid"],
                    *[":" + m + ":iosSimulatorArm64Test" for m in modules]], check=True)

if __name__ == "__main__":
    main()

