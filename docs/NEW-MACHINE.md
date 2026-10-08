# Continuing development on another machine

Firedown links two compiled dependencies that are built in their own repos and
take hours (GeckoView: a Docker Gecko build): the FFmpeg `.so` set from
[firedown-ffmpeg](https://github.com/solarizeddev/firedown-ffmpeg) and the
patched GeckoView AAR from
[firedown-geckoview](https://github.com/solarizeddev/firedown-geckoview). Neither
is in git. They travel as **GitHub Release assets** of those repos, **pinned** in
this repo's `gradle.properties`, and **fetched automatically** at Gradle sync.
So a new machine needs no library toolchain — only the pinned releases to exist.

```
 build box                                   any other machine
 ─────────                                   ─────────────────
 firedown-ffmpeg: ./ffmpeg-android-maker.sh  git clone firedown
   → scripts/publish-release.sh v9.0.2-1     gh auth login
         ─── GitHub Release (tar.gz+sha256) ──▶  ./gradlew assembleDebug
 firedown-geckoview: ./build.sh docker             └ settings.gradle → scripts/fetch-prebuilts.sh
   → ./build.sh publish                                 ├ external/ffmpeg/        ← firedown-ffmpeg@<pin>
         ─── GitHub Release (tar.gz+sha256) ──▶          └ ~/.m2/…/geckoview/     ← firedown-geckoview@geckoview-<pin>
 firedown: bump the two pins in gradle.properties, commit
```

## One-time, on the machine that holds the compiled libraries (do this FIRST)

The pins in `gradle.properties` name releases that must exist. From the box
where the libraries were compiled:

```bash
# FFmpeg — output/ must hold lib/{arm64-v8a,x86_64} + include/
cd firedown-ffmpeg
scripts/publish-release.sh v9.0.2-1          # tag = v<ffmpeg version>-<n>; must match parse-arguments.sh

# GeckoView — ~/.m2 must hold the pinned GECKOVIEW_VERSION (config/build.env)
cd ../firedown-geckoview
./build.sh publish                           # tag = geckoview-<version>, e.g. geckoview-157.0.20260924084938
```

Both refuse a dirty tree / an existing tag, sha256 the asset, and print the
`gradle.properties` line to pin. If the pins already match (they do for the
first publish), nothing else to do.

## On the new machine

1. Install: JDK 17, Android SDK (platform 37.1 + NDK as `app/build.gradle` wants;
   Android Studio installs these on first sync), `git`, `bash`, `tar`,
   `sha256sum`, and the **GitHub CLI**: `gh auth login` (the library repos are
   private; this is the only credential the fetch needs. Without `gh`, export
   `GITHUB_TOKEN` with read access to both repos instead.)
2. Clone the app and build:
   ```bash
   git clone https://github.com/solarizeddev/firedown.git && cd firedown
   ./gradlew assembleDebug      # or open in Android Studio — sync fetches the prebuilts
   ```
   The first sync prints `[prebuilts] downloading …`, verifies both sha256s,
   and installs FFmpeg into `external/ffmpeg/` and GeckoView into
   `~/.m2/repository/org/mozilla/geckoview/`. Every later sync is silent.
3. Only if you will **rebuild the libraries** there too, clone those repos as
   well; their `CLAUDE.md`s carry the toolchain story (Docker for Gecko; meson/
   ninja/nasm for FFmpeg's dav1d). A library built locally lands exactly where
   the fetch would put it and is never re-fetched or overwritten.

## Things git does NOT carry — copy by hand if the new machine needs them

| what | where | needed for |
|---|---|---|
| Release signing key | wherever the Studio release wizard keeps it (`app/release/` is only its `output-metadata.json`) | signed release APKs; debug builds need nothing |
| `firedown.mappingUploadToken` (+ optional `firedown.mappingUploadUrl`) | `~/.gradle/gradle.properties` | `assembleRelease` (the `uploadReleaseMapping` task fails loudly without it; `-Pfiredown.mappingUpload=false` skips it) |
| `gh` login / `GITHUB_TOKEN` | `gh auth login` | the prebuilt fetch, publishing releases |
| Gecko source + toolchain caches (`gecko/`, `.docker-build/`, `~/.mozbuild`) | firedown-geckoview, tens of GB | **don't copy** — `./build.sh docker` recreates them; a machine that only develops the app never needs them |
| FFmpeg `build/`, `sources/`, `output/` | firedown-ffmpeg | **don't copy** — the release carries `output/`; a rebuild recreates the rest |
| `local.properties` (`sdk.dir`) | app root | Studio writes it on first open |

## Bumping a library

1. Rebuild it on whichever box has the toolchain.
2. Publish: `scripts/publish-release.sh v9.0.2-2` (ffmpeg) or `./build.sh publish`
   (GeckoView — a NEW `GECKOVIEW_BUILD_DATE` per published build; a coordinate is
   published once, the script refuses a second upload under the same version).
3. In this repo change the pin in `gradle.properties`, commit.
4. Other machines: next sync fetches it. An **older** copy already present is
   not replaced silently (FFmpeg: `version.txt` differs → warning; GeckoView:
   a different version is a different `~/.m2` dir, so the new one is simply
   fetched beside it) — `scripts/fetch-prebuilts.sh all --force` replaces.

## Why Releases, not git LFS / GitHub Packages / committing the binaries

The AAR is ~165 MB and the `.so` set tens of MB, changing only when a library
is rebuilt. Committing them (or LFS) keeps every revision in every clone forever
and LFS bandwidth on a private repo is metered; GitHub Packages' free private
tier holds about three AARs. A Release asset is free, unmetered, up to 2 GB,
addressed by a tag, and `gh` already carries the private-repo credential. The
cost is the two pins and `gh auth login` once per machine.
