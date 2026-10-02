#!/usr/bin/env python3
"""Run metadata-only instrumentation from an external Swift fixture catalog on a local emulator."""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import tempfile


def run(command, **kwargs):
    try:
        return subprocess.run(command, check=True, **kwargs)
    except subprocess.CalledProcessError as error:
        if error.stdout: print(error.stdout)
        if error.stderr: print(error.stderr)
        raise


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=Path, required=True)
    parser.add_argument("--media", type=Path, required=True)
    parser.add_argument("--serial", required=True, help="An already-running local emulator")
    parser.add_argument("--evidence", type=Path, required=True, help="External evidence directory")
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[1]
    adb = str(Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb")
    prefix = [adb, "-s", args.serial]
    qemu = run(prefix + ["shell", "getprop", "ro.kernel.qemu"], capture_output=True, text=True).stdout.strip()
    if qemu != "1":
        parser.error("This runner requires a local emulator; physical-device participation needs separate authorization.")
    evidence = args.evidence.resolve()
    product_root = Path(run(["git", "-C", str(repo), "rev-parse", "--path-format=absolute", "--git-common-dir"],
                            capture_output=True, text=True).stdout.strip()).parent
    if evidence.is_relative_to(product_root):
        parser.error("Keep fixture binaries and evidence outside the product checkout.")
    evidence.mkdir(parents=True, exist_ok=True)
    catalog = json.loads(args.catalog.read_text())
    if catalog["schemaVersion"] != 1:
        parser.error("Unsupported shared catalog version")
    names = set()
    ids = set()
    for fixture in catalog["fixtures"]:
        fixture_id = fixture["id"]
        if not re.fullmatch(r"[a-z0-9][a-z0-9-]+", fixture_id) or fixture_id in ids:
            parser.error("Fixture IDs must be unique safe identifiers")
        ids.add(fixture_id)
        name = fixture["canonicalFilename"]
        if Path(name).name != name or name in names:
            parser.error("Fixture filenames must be unique basenames")
        names.add(name)
        source = args.media / name
        if source.stat().st_size != fixture["sizeBytes"] or sha256(source) != fixture["sha256"]:
            parser.error("Fixture integrity failed: " + fixture["id"])
    print("Verified", len(names), "external fixtures", flush=True)
    stage = Path(tempfile.mkdtemp(prefix="android-metadata-assets-"))
    package = "com.mux.video.upload.test"
    device_dir = "/sdcard/Android/data/" + package + "/files/metadata-fixtures"
    try:
        assets = stage / "assets/fixtures"
        assets.mkdir(parents=True)
        multi = stage / "android-distinct-multi-audio.mp4"
        source = args.media / "standard-h264-sdr-1080p30-aac-stereo.mp4"
        run(["ffmpeg", "-v", "error", "-nostdin", "-y", "-i", str(source),
             "-f", "lavfi", "-i", "sine=frequency=880:sample_rate=48000:duration=3",
             "-map", "0:v:0", "-map", "0:a:0", "-map", "1:a:0", "-c:v", "copy",
             "-c:a", "aac", "-ac:a:0", "2", "-ac:a:1", "1", "-disposition:a:0", "0",
             "-disposition:a:1", "default", "-shortest", str(multi)])
        if "android-distinct-multi-audio" in ids or "android-malformed-container" in ids:
            parser.error("Android overlay fixture IDs already exist in the source catalog")
        source_fixture = next(f for f in catalog["fixtures"] if f["canonicalFilename"] == source.name)
        catalog["fixtures"].append({
            "id": "android-distinct-multi-audio", "canonicalFilename": multi.name,
            "sizeBytes": multi.stat().st_size, "sha256": sha256(multi),
            "materialization": {"kind": "derived", "sourceSha256": source_fixture["sha256"]},
            "facts": {"videoCodec": "h264", "encodedDimensions": [1920, 1080],
                      "displayDimensions": [1920, 1080], "dynamicRange": "sdr",
                      "audioLayouts": ["Stereo", "Mono"]},
        })
        malformed = stage / "android-malformed-container.mp4"
        malformed.write_bytes(bytes([42]) * 64)
        catalog["fixtures"].append({
            "id": "android-malformed-container", "canonicalFilename": malformed.name,
            "sizeBytes": malformed.stat().st_size, "sha256": sha256(malformed),
            "materialization": {"kind": "generated"}, "facts": {},
            "expectedMetadataFailure": True,
        })
        (assets / "catalog.json").write_text(json.dumps(catalog, indent=2))
        (evidence / "run-catalog.json").write_text(json.dumps(catalog, indent=2))
        (evidence / "ffmpeg-version.txt").write_text(run(["ffmpeg", "-version"], capture_output=True, text=True).stdout)
        env = dict(os.environ, ANDROID_SERIAL=args.serial)
        with (evidence / "build.log").open("w") as log:
            run([str(repo / "gradlew"), ":library:assembleDebugAndroidTest",
                 "-PmediaFixtureAssets=" + str(stage / "assets"), "--console=plain"],
                cwd=repo, env=env, stdout=log, stderr=subprocess.STDOUT)
        apk = repo / "library/build/outputs/apk/androidTest/debug/library-debug-androidTest.apk"
        run(prefix + ["install", "-r", "-t", str(apk)], capture_output=True)
        run(prefix + ["shell", "mkdir", "-p", device_dir])
        results = []
        for fixture in catalog["fixtures"]:
            # Leave space for emulator services; never clear unrelated apps or data.
            available = int(run(prefix + ["shell", "df", "-k", "/data"],
                                capture_output=True, text=True).stdout.splitlines()[-1].split()[3]) * 1024
            if fixture["sizeBytes"] + 128 * 1024 * 1024 > available:
                results.append({"id": fixture["id"], "status": "insufficientEmulatorStorage"})
                continue
            local = stage / fixture["canonicalFilename"] if fixture["id"].startswith("android-") else args.media / fixture["canonicalFilename"]
            remote = device_dir + "/" + fixture["canonicalFilename"]
            try:
                run(prefix + ["push", str(local), remote], capture_output=True)
                output = run(prefix + ["shell", "am", "instrument", "-w", "-r",
                    "-e", "class", "com.mux.video.upload.internal.standardization.MediaMetadataFixtureTests",
                    "-e", "fixtureId", fixture["id"], package + "/androidx.test.runner.AndroidJUnitRunner"],
                    capture_output=True, text=True).stdout
                (evidence / (fixture["id"] + ".log")).write_text(output)
                if "FAILURES!!!" in output or "OK (2 tests)" not in output:
                    raise RuntimeError("Instrumentation failed: " + fixture["id"])
                report = run(prefix + ["exec-out", "run-as", package, "cat", "files/metadata-results.json"],
                             capture_output=True).stdout
                data = json.loads(report)
                results.extend(data["results"])
                data["results"] = results
                (evidence / "metadata-results.json").write_text(json.dumps(data, indent=2))
                print("Inspected", fixture["id"], flush=True)
            finally:
                run(prefix + ["shell", "rm", "-f", remote])
        print("Evidence:", evidence, flush=True)
    finally:
        shutil.rmtree(stage)


if __name__ == "__main__":
    main()
