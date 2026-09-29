#!/usr/bin/env python3
"""Build isolated Maven/AAR consumers; keep generated projects and evidence outside Git."""
import argparse
import hashlib
import json
import os
import shutil
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[2]
MATRIX = [
    # name, Kotlin, AGP, compileSdk, minSdk, Mux, alignment, Transformer probe, expected failure
    ('upload-kotlin22', '2.2.10', '8.13.0', 36, 23, False, False, False, None),
    ('upload-kotlin20', '2.0.10', '8.13.0', 36, 23, False, False, False, 'metadata'),
    ('upload-agp810', '2.2.10', '8.10.0', 36, 23, False, False, False, None),
    ('upload-agp89', '2.2.10', '8.9.1', 36, 23, False, False, False, 'agp'),
    ('compile35', '2.2.10', '8.13.0', 35, 23, False, False, False, 'compileSdk'),
    ('min21', '2.2.10', '8.13.0', 36, 21, False, False, False, 'minSdk'),
    ('mux-compile36', '2.2.10', '8.13.0', 36, 23, True, False, False, 'muxCompileSdk'),
    ('mux-default', '2.2.10', '9.2.1', 37, 23, True, False, False, None),
    ('mux-aligned', '2.2.10', '9.2.1', 37, 23, True, True, False, None),
    ('transformer-probe', '2.2.10', '8.13.0', 36, 23, False, False, True, None),
]


def run(command, directory, log):
    with log.open('w') as output:
        return subprocess.run(command, cwd=directory, stdout=output,
                              stderr=subprocess.STDOUT, check=False).returncode


def artifact(repository):
    poms = list((repository / 'com/mux/video/upload').glob('*/*.pom'))
    if len(poms) != 1:
        raise ValueError('Use an isolated repository containing exactly one Upload SDK version')
    pom = poms[0]
    ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
    version = ET.parse(pom).findtext('m:version', namespaces=ns)
    aar = pom.with_suffix('.aar')
    with zipfile.ZipFile(aar) as archive:
        metadata = archive.read('META-INF/com/android/build/gradle/aar-metadata.properties').decode()
    return version, {'version': version, 'aar_sha256': hashlib.sha256(aar.read_bytes()).hexdigest(),
                     'aar_bytes': aar.stat().st_size, 'aar_metadata': metadata}


def consumer(directory, repository, version, case):
    name, kotlin, agp, sdk, minimum, mux, align, transformer, _ = case
    modern = agp.startswith('9.')
    source = directory / 'app/src/main/java/compat/SmokeActivity.kt'
    source.parent.mkdir(parents=True)
    (directory / 'settings.gradle').write_text('''pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    maven { url = uri(providers.gradleProperty('sdkRepository').get()) }
    google(); mavenCentral()
    maven { url = uri('https://muxinc.jfrog.io/artifactory/default-maven-release-local') }
  }
}
rootProject.name = 'UploadAarConsumer'
include ':app'
''')
    kotlin_plugin = '' if modern else f"  id 'org.jetbrains.kotlin.android' version '{kotlin}' apply false\n"
    (directory / 'build.gradle').write_text(f'''plugins {{
  id 'com.android.application' version '{agp}' apply false
{kotlin_plugin}}}
''')
    (directory / 'gradle.properties').write_text('android.useAndroidX=true\norg.gradle.jvmargs=-Xmx2048m\n')
    (directory / 'app/build.gradle').write_text(f'''plugins {{ id 'com.android.application'; {"" if modern else "id 'org.jetbrains.kotlin.android'"} }}
android {{
  namespace 'compat'
  compileSdk {sdk}
  defaultConfig {{ applicationId 'compat.upload'; minSdk {minimum}; targetSdk 36; versionCode 1; versionName '1' }}
  buildTypes {{ release {{ minifyEnabled true; shrinkResources true; proguardFiles getDefaultProguardFile('proguard-android-optimize.txt') }} }}
  compileOptions {{ sourceCompatibility JavaVersion.VERSION_1_8; targetCompatibility JavaVersion.VERSION_1_8 }}
}}
kotlin {{ compilerOptions {{ jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8 }} }}
dependencies {{
  implementation 'com.mux.video:upload:{version}'
''' + ('''  implementation 'com.mux.player:android:1.7.0'
  implementation 'com.mux.stats.sdk.muxstats:data-media3-at_1_11:1.13.2'
''' if mux else '') + ('''  implementation 'androidx.media3:media3-transformer:1.11.1'
  implementation 'androidx.media3:media3-effect:1.11.1'
''' if transformer else '') + '''}
''' + ('''// Application-owned alignment; the Upload SDK does not force consumer versions.
configurations.configureEach {
  resolutionStrategy.eachDependency {
    if (requested.group == 'androidx.media3') useVersion '1.11.1'
  }
}
''' if align else '') + '''tasks.register('resolvedMedia3') {
  doLast {
    def modules = configurations.releaseRuntimeClasspath.resolvedConfiguration.resolvedArtifacts
      .findAll { it.moduleVersion.id.group == 'androidx.media3' }
      .collect { "${it.moduleVersion.id.name}:${it.moduleVersion.id.version}" }.unique().sort()
    file('media3.txt').text = modules.join('\\n') + '\\n'
  }
}
''')
    (source.parents[2] / 'AndroidManifest.xml').write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android">
<application android:theme="@android:style/Theme.Material.Light.NoActionBar">
<activity android:name="compat.SmokeActivity" android:exported="true"><intent-filter>
<action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/>
</intent-filter></activity></application></manifest>
''')
    # This Activity is compiled and shrunk, never launched; no uploads or analytics run.
    source.write_text('''package compat
import android.app.Activity
import android.os.Bundle
import com.mux.video.upload.MuxUploadSdk
import com.mux.video.upload.api.MuxUpload
import java.io.File
class SmokeActivity : Activity() {
  override fun onCreate(state: Bundle?) {
    super.onCreate(state)
    MuxUploadSdk.initialize(this, false)
    val upload = MuxUpload.Builder("https://example.invalid/upload", File(cacheDir, "source.mp4")).build()
    title = upload.currentProgress.toString()
    upload.start()
    upload.pause()
    upload.cancel()
''' + ('''    val player = com.mux.player.MuxPlayer.Builder(this).build()
    title = player.playbackState.toString()
    player.release()
    title = com.mux.stats.sdk.muxstats.MuxStatsSdkMedia3::class.java.name
''' if mux else '') + ('''    val transformer = androidx.media3.transformer.Transformer.Builder(this).build()
    val item = androidx.media3.transformer.EditedMediaItem.Builder(
      androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(File(cacheDir, "source.mp4")))
    ).setEffects(androidx.media3.transformer.Effects(emptyList(), listOf(
      androidx.media3.effect.Presentation.createForHeight(1080)
    ))).build()
    transformer.start(item, File(cacheDir, "output.mp4").absolutePath)
    transformer.cancel()
''' if transformer else '') + '''  }
}
''')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, help='New/empty external evidence directory')
    parser.add_argument('--repository', type=Path, help='Already packaged SDK Maven repository')
    parser.add_argument('--baseline-repository', type=Path, help='Unchanged SDK for same-app size comparison')
    parser.add_argument('--cases', nargs='+', help='Run only named matrix rows')
    args = parser.parse_args()
    if not os.environ.get('ANDROID_HOME'):
        parser.error('Set ANDROID_HOME to the installed Android SDK directory')
    output = args.output.resolve() if args.output else Path(tempfile.mkdtemp(prefix='upload-compat-'))
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        parser.error('--output must be empty to avoid stale evidence')
    repository = args.repository.resolve() if args.repository else output / 'maven'
    wrapper = str(ROOT / 'gradlew')
    if not args.repository:
        status = run([wrapper, ':library:publishReleasePublicationToMavenLocal',
                      f'-Dmaven.repo.local={repository}', '--console=plain'], ROOT, output / 'package.log')
        if status:
            raise SystemExit('SDK packaging failed; see package.log')
    version, packaged = artifact(repository)
    cases = [c for c in MATRIX if not args.cases or c[0] in args.cases]
    if args.cases and set(args.cases) - {c[0] for c in cases}:
        parser.error('Unknown case name')
    if args.baseline_repository:
        cases.append(('baseline', '2.2.10', '8.13.0', 36, 23, False, False, False, None))
    summary = {'java_home': os.environ.get('JAVA_HOME'), 'artifact': packaged, 'cases': []}
    if args.baseline_repository:
        summary['baseline_artifact'] = artifact(args.baseline_repository.resolve())[1]
    for case in cases:
        directory = output / case[0]
        selected_repo = args.baseline_repository.resolve() if case[0] == 'baseline' else repository
        selected_version, _ = artifact(selected_repo)
        consumer(directory, selected_repo, selected_version, case)
        case_wrapper = wrapper
        if case[2].startswith('9.'):
            shutil.copy2(ROOT / 'gradlew', directory / 'gradlew')
            wrapper_dir = directory / 'gradle/wrapper'
            wrapper_dir.mkdir(parents=True)
            shutil.copy2(ROOT / 'gradle/wrapper/gradle-wrapper.jar', wrapper_dir)
            properties = (ROOT / 'gradle/wrapper/gradle-wrapper.properties').read_text()
            (wrapper_dir / 'gradle-wrapper.properties').write_text(
                properties.replace('gradle-8.13-bin', 'gradle-9.5.1-bin'))
            case_wrapper = str(directory / 'gradlew')
        command = [case_wrapper, '-p', str(directory), f'-PsdkRepository={selected_repo}', '--console=plain']
        graph_status = run(command + [':app:resolvedMedia3', ':app:dependencies', '--configuration',
                                     'releaseRuntimeClasspath'], ROOT, directory / 'dependencies.log')
        status = run(command + [':app:assembleRelease'], ROOT, directory / 'build.log')
        modules_file = directory / 'app/media3.txt'
        modules = modules_file.read_text().strip().splitlines() if modules_file.exists() else []
        apk = directory / 'app/build/outputs/apk/release/app-release-unsigned.apk'
        row = {'name': case[0], 'kotlin': case[1], 'agp': case[2], 'compileSdk': case[3],
               'minSdk': case[4], 'gradle': '9.5.1' if case[2].startswith('9.') else '8.13',
               'built_in_kotlin': case[2].startswith('9.'), 'exit_code': status, 'graph_exit_code': graph_status,
               'expected_failure': case[8], 'media3': modules,
               'apk_bytes': apk.stat().st_size if apk.exists() else None}
        log = (directory / 'build.log').read_text()
        failure_markers = {'metadata': 'incompatible version of Kotlin',
                           'compileSdk': 'compile against version 36',
                           'minSdk': 'minSdkVersion 21 cannot be smaller than version 23',
                           'agp': 'requires Android Gradle plugin 8.10.0 or higher',
                           'muxCompileSdk': 'compile against version 37'}
        row['as_expected'] = (status == 0 and graph_status == 0
                              and 'An error occurred when parsing kotlin metadata' not in log) if case[8] is None else (
            status != 0 and failure_markers[case[8]] in log)
        if case[5] or case[7]:
            row['as_expected'] = row['as_expected'] and bool(modules) and all(
                module.endswith(':1.11.1') for module in modules)
        summary['cases'].append(row)
        (output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
        print(json.dumps(row), flush=True)
    raise SystemExit(0 if all(row['as_expected'] for row in summary['cases']) else 1)


if __name__ == '__main__':
    main()
