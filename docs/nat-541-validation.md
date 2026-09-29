# NAT-541 validation snapshot

Validated September 29, 2026 against baseline
`8620f064d2dbe51501c8dfe7753cee1ec50341fb` (`v1.0.4`). This is build evidence for
the HEVC feature branch; it does not establish codec/device or live ingest behavior.

## Selected build

| Setting | Selected value |
| --- | --- |
| Minimum Android API, SDK and sample | 23 |
| Media3 common/container/extractor/effect/transformer | 1.11.1 |
| Kotlin Android and Compose plugins | 2.2.10 |
| Producer AGP / Gradle / JDK | 8.13.0 / 8.13 / Microsoft OpenJDK 17.0.18 |
| SDK and sample compile SDK | 36 |
| Sample target SDK | 35, retained |
| Upload AAR minimum compile SDK / AGP | 36 / 8.10.0 |
| Library Java/Kotlin bytecode target | Java 8, retained |

The normal distribution plugin successfully packaged the release AAR, sources,
Javadocs, POM, and Gradle module metadata in an isolated local Maven repository.
The POM contains all five pinned Media3 implementation dependencies. The AAR's
metadata explicitly declares the consumer requirements. The existing unit suite
passed **9 debug + 9 release tests, zero failures** before and after the Kotlin/
Media3/API change. The sample release APK also built successfully.

Selected AAR SHA-256:
`cc0ffdd94e4a99b1c6151acde6fb99811b6a4d95ba5350d5fc3e0fc86750bdf3`.
Validation was performed before committing the build changes. The packaged
development version identifies the ticket branch and baseline hash. The external evidence summary additionally
hashes the producer build files to identify those inputs precisely.

## Packaged consumer matrix

All rows use `minSdk=23` except the explicit API 21 boundary. Every positive row
builds an unsigned release APK with R8 and resource shrinking enabled, without
custom keep rules or missing-class suppression. Apps exercise Upload's public API
in compiled code; they are never installed or launched.

| Case | Kotlin | AGP / Gradle | compileSdk | Result |
| --- | --- | --- | --- | --- |
| Upload | 2.2.10 | 8.13.0 / 8.13 | 36 | Build and R8 pass |
| Older Kotlin | 2.0.10 | 8.13.0 / 8.13 | 36 | Expected failure: cannot read Kotlin metadata 2.2 |
| Minimum AGP | 2.2.10 | 8.10.0 / 8.13 | 36 | Build and R8 pass; no Kotlin metadata parsing warnings |
| Older AGP | 2.2.10 | 8.9.1 / 8.13 | 36 | Expected failure: Upload AAR requires AGP 8.10.0 |
| Older compile SDK | 2.2.10 | 8.13.0 / 8.13 | 35 | Expected failure: AAR metadata requires compile SDK 36 |
| API 21 app | 2.2.10 | 8.13.0 / 8.13 | 36 | Expected failure: manifest merge requires API 23 |
| Upload + Player/Data, SDK 36 | 2.2.10 | 8.13.0 / 8.13 | 36 | Expected failure: published Player/Data AARs require SDK 37 |
| Upload + Player/Data, default resolution | 2.2.10 built in | 9.2.1 / 9.5.1 | 37 | Build and R8 pass; all 13 Media3 modules resolve to 1.11.1 |
| Upload + Player/Data, explicit alignment | 2.2.10 built in | 9.2.1 / 9.5.1 | 37 | Build and R8 pass; same resolved modules and APK size |
| Upload + retained Transformer resize/export probe | 2.2.10 | 8.13.0 / 8.13 | 36 | Build and R8 pass |
| Unchanged Upload baseline, same consumer | 2.2.10 | 8.13.0 / 8.13 | 36 | Build and R8 pass |

**11/11 checks produced their expected results: six builds and five specific
compatibility failures.** The Transformer row was re-run after strengthening the
probe to retain a resize/export entry point; the consolidated evidence uses that
result rather than the earlier builder-only probe.

The Player/Data rows consume `com.mux.player:android:1.7.0` and
`com.mux.stats.sdk.muxstats:data-media3-at_1_11:1.13.2` from Mux Maven. Their
published metadata requires `minCompileSdk=37`; Upload alone requires 36.
The AGP 9 consumer compiler classpath was separately verified to contain
`kotlin-compiler-embeddable:2.2.10`.

The unforced Gradle graph aligns all resolved Media3 modules to 1.11.1 through
published constraints. It also resolves Kotlin stdlib 2.2.20 and coroutines
1.11.0 for Player/Data, versus stdlib 2.2.10 and coroutines 1.9.0 for Upload alone.
The Upload SDK does not introduce a global resolution strategy. Resolution/build
success does not prove Player/Data runtime behavior or media export behavior.

An exploratory AGP 8.9.1 build before declaring the AAR AGP floor succeeded with
R8 Kotlin metadata parsing warnings. The final package rejects that configuration
at the metadata check. Kotlin 2.0 compatibility is not claimed, and neither
metadata-skip flags nor manifest overrides were used.

## Release size

| Universal unsigned release APK | Bytes | Difference from same-app baseline |
| --- | ---: | ---: |
| Unchanged Upload baseline | 3,827,333 | — |
| Upload with pinned Media3 dependencies, before adapter integration | 3,811,379 | -15,954 |
| Upload plus retained Transformer resize/export probe | 4,391,771 | +564,438 |
| Upload plus Player/Data on AGP 9.2.1 | 5,014,910 | Different app/toolchain; not a controlled delta |

The first three apps use the same Kotlin/AGP/SDK, Upload API references, and R8
settings. Unreferenced Media3 implementation is removed from the Upload-only app
until the adapter is integrated. The export probe estimates retained pipeline
cost; it is not a final HEVC feature size or a device download-size estimate.
Existing libyuv/native dependencies remain; removing the old transcoder is outside
this ticket. Re-measure after adapter/lifecycle integration.

## Reproduction and acceptance boundary

[Consumer matrix instructions](../tools/consumer-compatibility/README.md) describe
fresh local Maven packaging and the external output layout. The matrix retains
artifact hashes, compiler/build inputs, dependency graphs, exact failure logs,
APKs, R8 mappings, and generated consumer projects. No binary fixtures or logs
are added to product Git.

Independent review, feature-branch merge, and human review remain pending when
this draft PR is created. Physical API 23,
HEVC/Main10/HDR, quality, performance, Player/Data runtime behavior, and live
Mux ingest remain separate gates; this ticket does not supply that proof.
