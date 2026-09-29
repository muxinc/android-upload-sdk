# Packaged AAR compatibility checks

Requires Python 3, JDK 17, Android SDK platforms 35/36/37 and build tools,
and access to Google Maven, Maven Central, and Mux Maven. Set `JAVA_HOME` and
`ANDROID_HOME`, then run from the repository root:

```sh
python3 tools/consumer-compatibility/run.py --output /tmp/upload-consumer-results
```

Use a new/empty output directory. The script packages the SDK into an isolated
local Maven repository and builds consumer release apps with R8 enabled.
It covers Kotlin 2.0/2.1/2.2, AGP/SDK boundaries, Mux Player/Data resolution, and
a retained Transformer export path. Apps are never installed or launched.
AGP 8 rows use Gradle 8.13; AGP 9.2.1 rows use Gradle 9.5.1 and built-in Kotlin.

Results, dependency graphs, APKs, and R8 mappings stay in the output directory.
`summary.json` records artifact hashes, resolved versions, exit codes, and
universal APK sizes. Expected incompatibilities require the specific diagnostic;
unexpected outcomes return a nonzero exit code.

Options:

- `--cases upload-kotlin22 compile35`: run selected rows listed in `run.py`.
- `--repository /tmp/upload-package`: use an existing isolated Maven publication.
- `--baseline-repository /tmp/upload-base`: compare against the unchanged SDK in
  the same consumer app. Each repository must contain exactly one Upload version.

Create a local publication with:

```sh
./gradlew :library:publishReleasePublicationToMavenLocal -Dmaven.repo.local=/tmp/upload-package
```
