# Metadata fixture instrumentation

Run the library's metadata inspector on a local emulator using the shared Swift fixture catalog and an external media directory. Python 3.9+, JDK 17, an Android SDK with `adb`, and FFmpeg are required. No media binary is stored in this repository.

```sh
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
python3 scripts/run-metadata-fixtures.py \
  --catalog /path/to/swift-upload-sdk/Tests/MediaFixtures/catalog.json \
  --media /path/to/external-media \
  --serial emulator-5554 \
  --evidence /tmp/android-metadata-evidence
```

Use an already-running local emulator with sufficient free storage. The runner does not create an emulator, clear existing app data, or use physical devices. It installs a library test APK, transfers one verified fixture at a time into that test app's external-files directory, and removes each staged input afterward. The test app remains installed for inspection. Fixtures too large for available storage are recorded as skipped; a skip is not successful inspection evidence.

The runner verifies the shared catalog's file sizes and SHA-256 values, packages only the catalog as test assets, and verifies each transferred file again before inspection. Temporary build assets and generated Android cases remain outside product Git. The evidence directory contains the run catalog, FFmpeg version, build log, per-fixture test logs, and `metadata-results.json`.

The shared catalog stays unchanged. The run catalog adds two Android cases: distinguishable stereo and mono audio tracks with the second marked default, and a malformed container. This establishes container-order inspection, not Mux primary/default ingest selection. The library test target SDK is set separately from the shipped library's consumer requirements.

Each readable fixture records a first inspection and ten repeated metadata inspections. Reported median, p90 and maximum exclude file transfer, fixture hashing and copying. The first call is not a cold-disk measurement: checksum validation has already read the file. These are local debug-emulator observations, not physical-device performance or release acceptance limits.

The inspector exposes typed normalized facts alongside per-track metadata and container evidence. It keeps extractor indices separate from physical container indices; Dolby Vision may expose both a Dolby view and an HEVC base view of one physical track. Container matrices provide normalized orientation while raw Android rotation remains visible. Reported duration, nominal rate, bitrate, encoder delay and padding remain observations, not effective-timeline, cadence, or bitrate measurements.

Compressed samples are not read by this implementation. GOP/IDR, sample timestamps/cadence, measured rates/bitrates, A/V start offset and effective duration/edit-list facts remain unknown. Unproven configurations, conflicting evidence, complex geometry and absent color stay unknown. HEVC multilayer/predicted reference-set configuration, AAC PCE/extension layouts, anamorphic container geometry and clean-aperture extensions need additional bounded metadata support before those properties can be claimed. API 23 color handling has unit/Robolectric coverage; this runner proves only the selected emulator's real platform behavior.

The ordinary unit/build check is:

```sh
./gradlew :library:testDebugUnitTest :library:testReleaseUnitTest \
  :library:assembleRelease :app:assembleRelease --console=plain
```
