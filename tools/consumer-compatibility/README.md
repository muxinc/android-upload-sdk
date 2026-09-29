# Packaged SDK consumer compatibility

Run this matrix before accepting a Media3/toolchain update. It builds separate apps
against the release Maven publication (AAR, POM, Gradle module metadata, and
transitive dependencies), rather than `project(':library')` or a bare AAR.
All generated projects, APKs, mappings, and logs stay in an external directory.
The apps are compiled and shrunk, never installed or launched.

Requirements: JDK 17, Python 3, an Android SDK with platforms 35, 36, and 37 plus the
required build tools, and access to Google Maven, Maven Central, and Mux Maven.
The repository wrapper supplies Gradle 8.13; the AGP 9.2.1 rows use a generated
wrapper pinned to Gradle 9.5.1 and AGP built-in Kotlin 2.2.10. SDK components should be installed
and licensed before running the matrix.

From the repository/worktree root:

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 17)" # macOS; use your JDK 17 path elsewhere
export ANDROID_HOME="$HOME/Library/Android/sdk" # use your SDK path elsewhere
python3 tools/consumer-compatibility/run.py --output /tmp/upload-consumer-results
```

Use a new/empty output directory. Packaging publishes only into its isolated
local Maven repository; it does not publish to Artifactory or Maven Central.
`summary.json` records the AAR SHA-256, metadata, matrix inputs, exit codes,
resolved Media3 modules, and universal unsigned release APK sizes. Each case
retains `build.log`, `dependencies.log`, generated source, R8 mappings, and APK.
Expected incompatibilities count as a successful matrix check only when the
specific diagnostic is present; unexpected failures return a nonzero exit code.

To reuse a package built with the normal distribution plugin:

```sh
./gradlew :library:publishReleasePublicationToMavenLocal \
  -Dmaven.repo.local=/tmp/upload-package
python3 tools/consumer-compatibility/run.py \
  --repository /tmp/upload-package --output /tmp/upload-consumer-results
```

Use a fresh Maven repository containing exactly one Upload SDK version. This
avoids reusing stale development coordinates after uncommitted changes.
`--cases upload-kotlin22 compile35` selects a subset of the named rows in
`run.py`. A full run includes Kotlin 2.0/2.2, AGP variants, SDK boundaries,
Mux Player/Data with default and explicit Media3 alignment, and a Transformer
reference that prevents R8 from removing the future media pipeline.

For a controlled size comparison, package the unchanged base checkout into a
second isolated repository and pass `--baseline-repository /tmp/upload-base`.
The baseline app uses the same Kotlin/AGP/SDK, source, and R8 settings as
`upload-kotlin22`. Compare those two APK sizes, not AAR sizes or the sample app.
The `transformer-probe` estimate includes an explicit Transformer reference;
actual size will change when the Upload adapter is integrated. These are
universal APK bytes, not Play Store download-size or per-device estimates.

This matrix establishes build compatibility only. API 23 device behavior,
HEVC/Main10/HDR capability, playback, export quality, and live ingest require
separate validation.
