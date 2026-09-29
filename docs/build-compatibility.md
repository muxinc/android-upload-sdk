# Android Upload SDK build compatibility

This branch pins the HEVC build foundation to Media3 **1.11.1**, Kotlin Android
and Compose plugins **2.2.10**, AGP **8.13.0**, Gradle **8.13**, and JDK **17**.
The Upload SDK and sample app minimum Android version is **API 23**. The sample
app compiles against API 36 and keeps its existing target SDK 35.

## Consumer requirements

- Use `minSdk >= 23` and `compileSdk >= 36` for Upload alone. The SDK AAR declares
  `minCompileSdk=36`; API 21 consumers fail manifest merging.
- Use Kotlin **2.2**. Kotlin 2.0.10 cannot read this SDK's Kotlin 2.2 metadata or
  the resolved Kotlin standard library. Do not bypass metadata checks to claim
  compatibility.
- Use AGP **8.10.0 or later** with a matching Gradle version. The AAR declares
  `minAgpVersion=8.10.0`. An exploratory AGP 8.9.1 R8 build produced an APK but
  warned that it could not parse Kotlin metadata; that is not a supported result.
- Mux Player **1.7.0** plus Mux Data **data-media3-at_1_11:1.13.2** additionally
  require `compileSdk >= 37` in their own published AAR metadata. This requirement
  comes from those artifacts, not from Upload. The consumer matrix uses AGP
  **9.2.1**, built-in Kotlin **2.2.10**, and Gradle **9.5.1** for that combination.

The API minimum and Kotlin upgrade affect existing customers and must be included
in the eventual release notes. These branch requirements are not claims about
an already published Upload SDK release.

## Dependency resolution

Media3 types remain implementation details of Upload. The Maven publication
carries its Media3 dependencies so consumers receive them without manually
listing Upload's internal dependencies. Always consume the SDK through Maven;
a bare AAR does not carry the dependency graph.

The current Gradle module metadata aligns Media3 modules to 1.11.1 when combining
Upload with the tested Player/Data releases. Inspect the resolved graph, not
only the POM: Player's POM requests 1.11.0, while Gradle applies Media3's alignment
constraints. The matrix checks the default graph and a separately aligned graph.
Upload does not globally force a customer's Media3 versions. Re-run consumer
and media validation when any of these dependencies changes; build success does
not prove media behavior against a different Transformer release.

## Reproduce validation

See [the packaged-AAR matrix](../tools/consumer-compatibility/README.md).
It uses the normal distribution plugin to publish into an isolated local Maven
repository and builds separate consumer apps with R8 enabled. Generated projects,
fixtures, APKs, logs, and mappings stay outside product Git.

The repository regression checks are:

```sh
./gradlew test :app:assembleRelease
```

The checks compile code, package dependencies, and run JVM tests. They do not
establish API 23 device behavior, HEVC/Main10/HDR conversion, playback, or live
Mux ingest. Those remain separate execution and acceptance gates.

Primary build references:

- [Media3 release notes](https://developer.android.com/jetpack/androidx/releases/media3)
- [Kotlin/AGP/R8 compatibility](https://developer.android.com/build/kotlin-support)
- [AAR metadata](https://developer.android.com/build/publish-library/prep-lib-release)
- [AGP 9.2 compatibility](https://developer.android.com/build/releases/agp-9-2-0-release-notes)
