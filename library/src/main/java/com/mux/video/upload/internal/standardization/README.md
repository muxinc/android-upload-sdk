# SDR adapter (NAT-547)

`SdrConversionAdapter.start` accepts the original file, its metadata and complete shared sample/timeline inspection, and the planner's `StandardInputConversion`. These must describe the same unchanged source. It exports on a dedicated `HandlerThread`, validates the generated file with `StandardInputOutputValidator`, and invokes one internal terminal callback on that thread. It does not start transport or emit public upload events.

- `Completed` transfers an explicitly owned `SdrGeneratedFile` and validated facts to orchestration. Keep it while upload/resume needs it; call its idempotent `delete()` at the terminal lifecycle point.
- `Failed` permits original-file fallback. No partial or rejected output is selected.
- `Cancelled` stops preparation even without a Transformer callback. Orchestration must suppress public results and must not upload the original as a cancellation fallback.
- If deletion fails, `Attempt.pendingCleanup` retains the exact deletion authority for retry. Nothing sweeps `cacheDir/mux-upload`, where customer source copies can live.

Each attempt allocates only its tracked unique `sdr-*.mp4` file under `cacheDir/mux-upload/standard-input`, after a size-based disk guard. It deletes that file on failure/cancellation, releases Transformer, and quits its thread. A successful file outlives that thread. Persisted payload identity, abandoned-file recovery, and generated resume belong to NAT-549; upload/pause orchestration belongs to NAT-548.

## Encoding contract

H.264 stays H.264, HEVC stays HEVC, and another proven SDR source uses H.264. Dimensions are the planner's exact display-oriented even dimensions, with orientation baked into pixels and no upscaling. Portrait encoding is explicitly enabled. Video always encodes, including an identity presentation effect, so bitrate/GOP remediation cannot silently remux the source.

The 8-bit 4:2:0 path requests H.264 Baseline (no B frames) or HEVC Main, VBR at 6 Mbps through a 2048-pixel long side or 15 Mbps above it, additionally capped to 75% of the plan's average-bitrate ceiling. GOP targets use 90% of the planned interval ceiling, rounded down to whole seconds, following Swift's headroom convention. Output validation remains the final bitrate, IDR/closed-GOP, profile, pixel, cadence, and timeline gate.

Media3's default video factory overrides H.264 profile requests. The adapter therefore supplies Media3 `DefaultCodec` with the exact platform profile, level, dimensions, bitrate, nominal rate, surface input, and GOP interval. Transformer owns decoding, effects, encoding, and muxing. The AAC delegate disables both encoder and format fallback. Every `onFallbackApplied`, mismatched output MIME, or invalid generated file fails the attempt; a listed encoder never proves a usable path.

The bounded advertised-format preflight examines at most 128 codecs and checks a 500 ms budget between platform calls. Native calls cannot be preempted. It checks source decode and exact output encode support before creating a file, and pins one supported video encoder. It does not turn advertised capabilities into the planner's device-path proof.

Preserved cadence keeps source timestamps. Constant-rate reduction is limited to proven constant cadence with an integral source/30 fps ratio; a one-part-per-million allowance covers microsecond-rounded rate measurement. Media3 drops frames at 30 fps and the shared validator checks actual output cadence and duration. Frame duplication, fractional-rate resampling, 10-bit SDR encoding, and HDR/tone mapping have no proof in this adapter and fail conservatively. NAT-550 owns tone mapping.

## Audio decision

[Shipped Swift v1.2.0](https://github.com/muxinc/swift-upload-sdk/blob/faa440e6ea9339315a9d192ca314c4ef5a8668c9/Sources/MuxUploadSDK/InputStandardization/UploadInputStandardizationWorker.swift#L794) copies AAC and otherwise preserves sample rate, channel count, and available layout while encoding AAC (160 kbps for mono/stereo; 384 kbps for surround). Following that behavior and the Android TDD's exclusion of new surround conversion, this adapter copies known compliant AAC mono/stereo/5.1, converts known non-AAC mono/stereo to AAC-LC at 160 kbps with the same rate/count, and rejects non-AAC surround or unknown layouts. It adds no downmix policy. Output channel layout and reported export sample rate/count must match these targets.

The asset loader's selector matches the first audio track's container ID, rather than Media3's preferred/default track. Missing identity fails; it never substitutes a secondary track.

## Evidence and remaining limits

At implementation time, 210 unit tests pass in each debug/release variant; release SDK/sample builds and lint-vital pass. External API 36 emulator checks cover six accepted exports: H.264 resize, silent H.264, rotated portrait, MP3 stereo to AAC, short HEVC Main, and 240-to-30 fps H.264. Two HEVC failures exercise safe rejection: 720p exceeds the emulator's 512-pixel encoder limit; a longer small export emits CRA/open GOP and fails the existing validator. Cancellation removes generated bytes and preserves source copies. These are nine cases across three instrumentation tests, including the grouped export matrix.

The distinguishable multi-audio source has 3s video, 3s first stereo AAC (track ID 2, non-default), and 6s secondary mono AAC (ID 3, default). Output contains the first stereo track: its copied AAC bytes have the same SHA-256 as source audio ordinal zero and differ from the default secondary. This proves local selection only; Mux ingest parity remains pending.

Media binaries, fixture harness, output probes, and measurements stay outside product Git in `/private/tmp/nat547-sdr`. API 23-29 execution, physical hardware, long HEVC closed-GOP support, 10-bit SDR, HDR, high-resolution device coverage, visual quality/performance acceptance, and a live Mux upload are unverified. Emulator timings do not establish physical-device performance limits. NAT-548 can use the validated SDR path for the early upload journey; no predecessor merge is required to continue the stack.
