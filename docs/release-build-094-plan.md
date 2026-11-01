# 0.9.4 release build: one script, a frozen F-Droid recipe, signing behind approval

Status: v8, READY after 7 adversarial review rounds (log at the end); awaiting the owner's go. Nothing is
implemented yet.

## Goal

1. Release files are built by one in-repo entry point on the owner's Mac and on GitHub Actions; F-Droid's server
   calls the same script for everything it needs before its own Gradle run. (Same *procedure*, not the same bytes:
   see R1.)
2. After one last fdroiddata MR (for 0.9.4), F-Droid's checkupdates bot ships every later tag with no MR from us —
   its own bot MR, merged by a maintainer after a green build (F2) — unless a recipe pin changes (F7), which the
   release tool reports before the tag.
3. A tag produces every release file in Actions; nothing is signed until the owner approves; the result is a draft
   GitHub release the owner publishes.
4. Agents never see, type or store a password or key; CI signs only after the owner's click. Today this is NOT
   "the owner keeps the only copy": `~/.android/debug.keystore` is the same key, world-readable with AGP's public
   default password, and every plain release/debug build on the Mac signs with it (F10). Closing that is D5; the
   limits of the CI protection are in C5.

Out of scope: key rotation (APK v3 lineage), F-Droid shipping our signature, App Store distribution.

## Verified facts

Read in fdroidserver master `cdfdce8` (2026-10-27), GitLab and GitHub, 2026-11-01.

F1. **How the bot copies build blocks** (`checkupdates.py` `checkupdates_app`, :548–:677). `UpdateCheckMode: Tags`
    walks the 5 newest matching tags, takes the first line of the subdir's Gradle file matching
    `\b[Vv]ersionCode\s*=?\s*["'(]*([0-9][0-9_]*)` (today `versionCode = 44`, androidApp/build.gradle.kts:60),
    applies every `VercodeOperation`, **sorts** the codes, and when `builds[-1].versionCode != CurrentVersionCode`
    deep-copies `builds[-len(vercodes):]`, zipping them with the sorted codes. Only `versionCode`, `versionName`,
    `commit` (tag → hash) and `disable` change, plus a few JDK apt lines dropped from `sudo`. `prebuild`, `srclibs`,
    `sudo`, `ndk`, `timeout`, `gradleprops`, `scanignore`, `scandelete` are copied verbatim. So the last four blocks
    must be in ascending ABI digit order (armeabi-v7a 1, arm64-v8a 2, x86 3, x86_64 4; build.gradle.kts:30), and
    nothing in a block may name a file, version or hash that changes per release.
F2. **Bot MRs.** !52356, !52766, !53660 (codes 14–16) were opened by `checkupdates-bot` ~05:40 UTC and merged by a
    maintainer ~2 h later after a green bot pipeline running a real `fdroid build`. If a maintainer pushes to the
    bot's app branch, the bot skips the app until that MR is resolved (checkupdates.py:744–750), and a closed bot
    MR's branch is not pruned automatically.
F3. **Why the bot cannot ship 0.9.3.** Bot MR !54923 ("Update LatentJam to 44") copied the 0.9.2 block; its build
    failed on 16 scanner errors (six new `.bin` in `androidApp/src/main/assets/ml`, ten fixtures in
    `core/smart/src/androidHostTest/resources`). Per-file scan lists are what breaks auto-updates.
F4. **Order in `fdroid build`** (classic `build.py`, which fdroiddata CI runs as `fdroid build --verbose --test
    --refresh-scanner --on-server --no-tarball`): init → patch → srclibs → rm → extlibs → **prebuild** → cleanup of
    `build/{generated,intermediates,outputs,reports,tmp,android-profile}` and `.gradle` in every Gradle dir
    (build.py:554–583) → **scanner** → Gradle. Files prebuild leaves in the build dir are scanned; files outside it
    are not. `sudo:` runs only with `--on-server` (otherwise skipped with a warning, build.py:502). Default build
    timeout 7200 s (build.py:1185). fdroidserver master also has a new split pipeline (`fetch_srclibs`,
    `execute_sudo`, `prepare_source`, `build_local_run`) where the scanner call is not wired in yet
    (build_local_run.py:690): a future change on F-Droid's side we cannot pin (R4).
F5. **Scanner path rules** (`scanner.py` :769–:797, :1151–:1166; `common.getpaths_map`). Entries are globbed, then
    matched by `startswith`; directories and globs are accepted (0.9.1–0.9.2 ship two directory entries; `fdroid
    lint` does not check them). Fatal: an entry matching nothing ("Non-exist") and an entry no flagged file fell
    under ("Unused"); a file is credited to the **first** matching entry, scanignore before scandelete, so a nested
    entry can starve. Flagged: `.so*`, `.a`, `.aar`, `.jar`, `.class`, `.dex`, `.zip`, `.gz`/`.tgz`, `.wasm`,
    `.apk`, binary content in extension-less/`.bin`/`.out`/`.exe` files, gradle "usual suspects" (rule list refreshed
    from the network: R4), unknown Maven repo URLs. `.onnx`/`.json`/`.txt` are not flagged.
F6. **0.9.3 on F-Droid.** MR **!54838** (branch `latentjam-0.9.3`, opened 2026-10-29) exists, its pipeline is
    green (a real arm64 ORT-from-source build passed on fdroiddata CI), label `waiting-on-response`: linsui asked
    about the inline python and on 2026-10-30 suggested `sed -i -e '/Generate the Android AAR package/,
    /WORKING_DIRECTORY/d' $$onnxruntime$$/cmake/onnxruntime_java.cmake` (checked against ORT v1.26.0
    `onnxruntime_java.cmake`:257–269: it removes exactly that `add_custom_command`). `srclibs/onnxruntime.yml`
    exists only in that MR, not on fdroiddata master.
F7. **Pins a frozen recipe cannot follow:** srclib `onnxruntime@v1.26.0` (= `libs.versions.toml` onnxruntime),
    `ndk: r28c` (= `ndkVersion = "28.2.13676358"` in core/{ort-ops,map-native,audio-dsp}), the `sudo` apt list,
    the scan entries, `timeout`, `gradleprops`. Everything inside our scripts is free to change.
F8. **Build server size.** fdroidserver's buildserver Vagrantfile defaults to Debian trixie, 1 CPU, 2 GB RAM
    (`buildserver/Vagrantfile`:8–38); trixie mounts `/tmp` as tmpfs. F-Droid's production sizing is not public to
    us, but production built and published 0.9.0–0.9.2 from this tree (f-droid.org API: codes 14–16), so it is
    larger than that default for the Gradle part; the 0.9.3 arm64 ORT block has never run on production (only on
    fdroiddata CI, in Docker).
F9. **GitHub.** Public repo → environments with required reviewers are free. The `gh` token agents use has
    `repo` + `workflow` scopes (enough to edit workflows, push tags, approve deployments via API, overwrite — not
    read — secrets). The existing `github-pages` environment has `can_admins_bypass: true` (GitHub's default).
F10. **Gradle signing today** (androidApp/build.gradle.kts:20–39, 101–104, 151): `latentjam.playSigning=true` both
    turns ABI splits off (AGP refuses a bundle with splits) and selects the upload key, failing without
    `keystore.properties`; F-Droid builds are unsigned; every other release build is signed with the host's debug
    keystore — on the owner's Mac that is the original `~/.android/debug.keystore`, i.e. the *same key* as the
    GitHub release key. There is no "unsigned release" switch for GitHub/Play builds.

## Design

### A. Build entry point `tools/release/build.sh`

```
build.sh   android               # per-ABI APKs (arm64, armv7, x86_64), unsigned, into build/release/<ver>/
build.sh   bundle                # Play AAB, unsigned
build.sh   ios                   # unsigned IPA
build.sh   fdroid-prebuild <abi> [ORT_SOURCE NDK SDK]   # the only command F-Droid's prebuild runs
build.sh   sign <dir>            # Mac only: apksigner/jarsigner with the local key, interactive prompt
```

(`build.sh sign` refuses already-signed input unless `--resign` is given — for D5(b) test builds only.)

Gradle switches (decoupled, replacing the double role of `playSigning`):
- `-Platentjam.unsigned=true` → release `signingConfig = null`. Used by every release path except F-Droid's
  (which is already unsigned via `latentjam.fdroid`). The sign steps refuse any input APK/AAB that already
  carries a signature.
- `-Platentjam.bundle=true` → ABI splits off (for `bundleRelease`; packages all four ABIs, as the universal
  F-Droid CI build already proves). `playSigning=true` stays as the local convenience = `bundle` + upload key;
  `unsigned` and `playSigning` together fail configuration.
- Path: everything lives under `tools/release/`, not `tools/build/` — `.gitignore:4` ignores `**/build/`, and the
  path is frozen into the F-Droid prebuild line.
- Plain `assembleRelease` without switches keeps signing with the debug keystore (the owner's phone test builds
  install as updates this way). That is a second path to the same key, on the owner's Mac only; build.sh never
  uses it.

`android`: builds the reduced arm64 ORT (CI: always from source, in tag and branch runs alike, no cache; Mac: cached under
`~/.cache/latentjam/ort/<key>`, key = hash of all of `tools/runtime/` + `libs.versions.toml` onnxruntime + NDK,
CMake, JDK versions), then runs the **plain** `./gradlew :androidApp:assembleRelease -Platentjam.unsigned=true
-Platentjam.reducedOrtRepo=…` (configuration cache as gradle.properties sets it — the 0.8.x trap), then
`apk_inventory.py` (16 KiB alignment, ABIs). SBOM runs as a separate Gradle invocation with
`--no-configuration-cache` (app.yml:63) so it never changes the shipped command.

`fdroid-prebuild <abi>`: installs the exact cmake versions with `sdkmanager`; for arm64 builds the reduced ORT
from the srclib with `LATENTJAM_ORT_WORK` and the mirror on disk at `$HOME/.cache/latentjam-ort` (outside the
scanned tree, not tmpfs — F8), writes the AAR to `build/reduced-ort/maven` (not under the cleaned dirs of F4),
and deletes the work dir afterwards. `LATENTJAM_ORT_OFFLINE` stays supported (R4).

Merge `build/ort-native-only` (f35f7196) first, after its own review round.

### B. Frozen F-Droid recipe

No file moves. Every block, ascending digit, fields in fdroidserver's `build_flags` order (`fdroid rewritemeta`
canonical):

Block order is fixed: 451 armeabi-v7a, **452 arm64-v8a**, 453 x86, 454 x86_64. The arm64-v8a block (452):

```yaml
  - versionName: 0.9.4
    versionCode: 452
    commit: <release commit hash>
    timeout: <measured, see E3; omitted if not needed>
    subdir: androidApp
    sudo:
      - apt-get update
      - apt-get install -y g++ make ninja-build python3-flatbuffers
    gradle:
      - yes
    srclibs:
      - onnxruntime@v1.26.0
    prebuild: bash ../tools/release/build.sh fdroid-prebuild arm64-v8a $$onnxruntime$$ $$NDK$$ $$SDK$$
    scanignore:
      - androidApp/src/main/assets/ml/*.bin
      - build/reduced-ort/maven
    scandelete:
      - tools/fuzz/corpus
      - core/smart/src/commonTest/resources
      - core/smart/src/androidHostTest/resources
      - iosApp/iosApp/ml
    ndk: r28c
    gradleprops:
      - latentjam.fdroid=true
      - latentjam.fdroidAbi=arm64-v8a
```

The other three blocks are the same without `timeout`, `sudo`, `srclibs` and the `build/reduced-ort/maven`
scanignore, with `prebuild: bash ../tools/release/build.sh fdroid-prebuild <abi>` and their own `fdroidAbi`.

plus `AutoUpdateMode: Version`, `UpdateCheckMode: Tags ^v[0-9.]+$`, `VercodeOperation: 10 * %c + 1 … 10 * %c + 4`, `Repo:` in master's form. `render` emits
through fdroidserver's own `metadata.write_yaml` (canonical folding of the long prebuild line, no comments), so
fdroiddata's rewritemeta gate never changes it.

- `*.bin` glob, not the whole directory: a `.so`/`.jar`/`.dex`/`.zip` dropped into the asset directory would still
  stop the build. The unshipped tables (`artist_colisten/family*/lfm/pop`, `audio_tagger`) stay in place (≈15 host
  tests and the iOS parity tests read them there) and are covered by the same glob; `ignoreAssetsPattern`
  keeps them out of every APK, and the F-Droid MR says so. If the reviewer wants them deleted rather than ignored,
  fallback: move them out of the asset dir (a real refactor: GoldenFiles.android.kt, the asset tests,
  `LJ_ML_DIR`, sbom/native_verify) in its own commit — not part of 0.9.4 unless asked.
- In-repo source of truth: `tools/release/fdroid/recipe.yml` + `tools/release/fdroid_recipe.py`:
  - `render --commit <hash>` takes a plain commit hash (the release commit exists before the tag; never pass an
  annotated tag object — peel with `^{commit}` if starting from a tag) and writes, with `CurrentVersion`,
  `CurrentVersionCode` (= 10×base + 4) and `AutoName`, (always with the real https `Repo:`; lint rejects any other, lint.py:2294) the metadata file (and `srclibs/onnxruntime.yml`);
  - `check` runs on a `git archive` export of HEAD in a temp dir (the real `scan_source` deletes scandelete hits,
    and the working tree holds ignored build outputs), with the arm64 block's prebuild output modelled by a
    placeholder AAR in `build/reduced-ort/maven`. It fails when (1) a scan entry is Non-exist or Unused under F5's rules incl. first-match crediting,
    (2) any file the scanner would flag lies outside the entries, (3) the rendered blocks differ in any field
    other than versionCode/versionName/commit/disable from the **last four blocks on fdroiddata master** (read
    via the GitLab API, since maintainers edit recipes on merge — then recipe.yml is updated to match, or it is a
    pin change), or the app-level fields the bot depends on changed (`AutoUpdateMode`, `UpdateCheckMode`,
    `VercodeOperation`; no `UpdateCheckData`, `Disabled`, `NoSourceSince`) → "this release needs an fdroiddata MR"
    (preflight then passes only once `release.py fdroid-mr` has rendered and lint-checked that MR for this exact
    commit), (4) fdroidserver's own `parse_androidmanifests` on the tree does not yield the
    expected versionCode/versionName, (5) the recipe's pins differ from the tree: srclib `onnxruntime@v<x>` vs
    `libs.versions.toml` onnxruntime, `ndk:` vs `ndkVersion` in core/{ort-ops,map-native,audio-dsp}. (5) is what
    turns an ORT/NDK bump into "update recipe.yml and open an MR" before the tag instead of a red bot MR after it.
  - The prebuild lines freeze `build.sh`'s CLI (path, `fdroid-prebuild` name, argument order, ABI names): a unit
    test runs each of the four frozen lines, with `$$…$$` substituted and a dry-run flag, against the current
    `build.sh`. Only that CLI is frozen; what the script does inside is free (F7).
  - Unit tests run `check` against fdroidserver's real `scanner.scan_source` on fixture trees generated at test
    time in a temp dir — never committed (committed binaries would themselves need scan entries).

### C. CI release workflow `.github/workflows/release.yml`

Trigger: push of tag `v*` (release) and push of a branch `release/**` (dry run; `GITHUB_SHA` is the pushed commit,
and each run uses the workflow file of its own commit). Not `workflow_dispatch`: GitHub only dispatches workflows
whose file is already on the default branch, which the first dry run's would not be. `sign`, `draft`, `play` carry
`if: github.event_name == 'push' && startsWith(github.ref, 'refs/tags/v')`.

1. `android` (ubuntu-24.04, timeout set, free-disk step): `build.sh android` + `build.sh bundle`; uploads
   unsigned APKs/AAB, SBOM, inventory; exposes the upload's artifact id as a job output.
2. `fdroid` — a matrix of four jobs, one ABI block each (a fresh container per block, because `--on-server`
   locks root and purges `sudo` after every build, build.py:487–495). Each job (a host job that frees runner disk,
   then `docker run`s the image — steps of a `container:` job cannot free host disk; timeout set; one container
   per block costs more than fdroiddata's reinstall-sudo loop but is simpler) runs
   `tools/release/ci_fdroid_build.sh`, a port of fdroiddata's own `fdroid build` CI job (`.gitlab-ci.yml`
   ~:342–:452): fdroidserver buildserver image pinned by digest plus fdroidserver itself installed from git master HEAD, as
   fdroiddata's `install_fdroid_server` does (the image alone is months old); the commit used is logged, and the
   local `check` fetches the same master, so our checks judge with what judges our MR and the bot's, an fdroiddata-like
   work dir with `config/` (categories for lint), `apt-get dist-upgrade`, `apt-get install sudo` and the
   openjdk-21 setup, fdroid-bootstrap-buildserver's production-hardening `.gitconfig` (master, as fdroiddata copies it), `sdkmanager` platform-tools/build-tools, `gradlew-fdroid` and `GRADLE_USER_HOME`, the
   existing `vagrant` user with `HOME=/home/vagrant` and `/etc/profile.d/bsenv.sh`, `fdroid fetchsrclibs`, chown;
   `render --commit $(git rev-parse "$GITHUB_SHA^{commit}")` into a git work dir with fdroiddata's `.gitignore`,
   committed (the commit is pushed in both the dry run and the tag run, so `fdroid build`
   clones the real https repo); `fdroid lint`, `fdroid rewritemeta` + `git diff --exit-code`, fdroiddata's
   `tools/check-fastlane.py` (changelog limits) and `tools/audit-gradle.py`, `fdroid build --verbose --test --refresh-scanner --on-server --no-tarball
   <appid>:<code>`. No `checkupdates` here: before a tag it fails by design, and on the tag it would first run on
   the real release and could block signing; fdroiddata's own MR `checkupdates` job, retried after the tag (E5),
   is that proof. `rewritemeta` gates as in fdroiddata's job (rewrite, then `git diff --exit-code`; `--list` alone
   exits 0, rewritemeta.py:90–93). Preflight requires all four green with `headSha` = the commit being tagged (`gh run list`).
3. `ios` (macos-15, Xcode 26.3): `build.sh ios`. Unproven on CI (Release K/N link with 8 GiB JVM settings): E3
   measures it; if it does not fit, the IPA is built on the Mac by `build.sh ios` and this job is dropped. Then
   the order is: draft → owner attaches the IPA → `release.py sums` regenerates and replaces `SHA256SUMS` →
   owner publishes → `post-publish` (altstore-source.py only sees releases that carry an IPA).
   In the tag run the four jobs are informational (not in `sign`'s `needs`): their gate is the green dry run on
   the same commit; scanner rules refreshed between dry run and tag must not block a release whose tag cannot
   move. A red tag-run fdroid job means: re-run it; if still red, the fdroiddata MR/bot MR will show it too and is
   answered there.
4. `sign` (`environment: release`; `needs:` `android`, whose `verify` step in
   `android` that fails unless `aapt2 dump badging` of every APK shows the expected versionName — the tag
   without `v` in a tag run, the `<ver>` of `release/<ver>` in a branch run — and versionCode = 10×base + ABI
   digit): downloads the `android` job's artifact **by id** (not by name: any job of the
   run, including the third-party fdroid container, can upload artifacts); runs only `actions/download-artifact`
   (pinned SHA) and build-tools 36.0.0
   binaries; no Gradle, no repository code. Keystore secret → `$RUNNER_TEMP`, `apksigner sign --ks-type PKCS12 --ks-key-alias release
   --ks-pass env:LJ_KS_PASS --key-pass env:LJ_KEY_PASS --v4-signing-enabled false` (v2+v3, minSdk from the APK),
   no `set -x`; then `apksigner verify --print-certs` (exactly one signer, SHA-256 = pinned DD:70:01:B3:…, v2
   present per `apksigner verify --verbose`; both digests normalised to lowercase hex without colons before comparing, and a test that a wrong
   certificate fails) and `zipalign -c -P 16 -v 4`; keystore deleted in an `if: always()` step. Uploads signed APKs only.
5. `draft` (`contents: write`; the workflow's top-level permissions are `contents: read`, every other job
   inherits that): downloads the signed APKs and the IPA/SBOM by artifact id too; `SHA256SUMS` over signed APKs + IPA + SBOM, `gh release create --draft --verify-tag` (fails unless the tag exists remotely), after checking via the API
   that no draft for the tag exists yet (a re-run must not make a second); notes from
   `fastlane/metadata/android/en-US/changelogs/<base>.txt` (single source; release.py copies it to the four ABI
   codes and checks they are identical) plus the annotated tag's message, which the owner writes with
   `git tag -a` (read with an explicit `git fetch --force origin refs/tags/<tag>:refs/tags/<tag>` or the API:
   a shallow checkout may not keep the tag object); he can still edit the draft text before publishing.
   `sign-dry` is its own job (`if: startsWith(github.ref, 'refs/heads/release/')`, `needs: android`, no
   `environment:`): same artifact-by-id download and the exact sign/verify/zipalign commands with a key generated
   in the job, asserting the certificate is not the pinned one.
   `draft` needs `sign` and `ios` when the ios job exists; with the Mac fallback it needs `sign` only.
   `concurrency`: group per ref; `cancel-in-progress` only for `refs/heads/release/` (tag runs are never
   cancelled — re-tagging is blocked by the ruleset). Dry-run branches are named `release/<ver>`; the old local
   `release/0.9.2`, `release/0.9.3-full` are never pushed again.
6. Play (phase 2, after the owner adds secrets): `sign-play` in `release` signs the AAB with **jarsigner** (upload
   key); a separate `play` job, in its own environment `play` restricted to `v*` tags (so `release/**` runs can
   never read it), holding only the service-account secret, uploads it as a draft on the closed-testing
   track (pinned action or Play Developer API script).

Hardening (C5, honest limits): all actions pinned by SHA; `setup-gradle` in release.yml with `cache-disabled:
true` (no Gradle cache written by `main` runs reaches a release); `release` environment with the owner as required
reviewer, **admin bypass off**, deployment policy = tags `v*`; a tag ruleset on `v*` restricting creation,
update (force-move re-fires the push event) and deletion; prevent-self-review stays off (he triggers and
approves). On a user-owned repo the ruleset's bypass identity is the owner's — the same identity as the agents'
token — so it guards against accidents and against `GITHUB_TOKEN` (e.g. the `contents: write` draft job)
creating or moving `v*` tags, not against that personal token. The approval page does not show the workflow
diff and the workflow runs from the tag commit, so `release.py tag` prints, for the owner to read before
approving, the full diff since the last tag of what handles the key (`.github/`, `tools/release/`) and a `--stat`
of everything that goes into the signed bytes (Gradle files, `build-logic/`, `tools/runtime/`, sources).
The `gh` token agents use can technically approve, push tags or edit workflows (F9): our rule is that agents
never do; the technical fix is a fine-grained token for agent sessions with Contents read-only and no
Actions/Environments/Secrets/Workflows write — agents then cannot push at all, which matches the existing rule
that only the owner pushes (decision D3).

### D. `tools/release/release.py`

Resumable state in `~/Documents/LJ/releases/<ver>/state.json`.

- `bump <version>`: versionName/versionCode, iOS `MARKETING_VERSION`/`CURRENT_PROJECT_VERSION`, changelog stub.
- `preflight`: clean tree; versionCode > last tag's; for every APK in `~/Documents/LJ/builds` and
  `~/Documents/LJ/releases/test-builds` (`aapt2 dump badging`) and the phone's installed code if `adb` sees it,
  each new split code (10×base + digit) > that APK's code when it is a split code (≥ 100), and the base > it
  when it is a base code; base > Play's highest (manual entry until phase 2); changelogs (all four identical, within fdroiddata's `check-fastlane.py` limits); `fdroid_recipe.py check`; release
  branch pushed (owner step) and a green `fdroid`+`android`(+`ios`) dry run on that exact commit; ≥ 30 GB free;
  `gh auth status`. (No GitLab token: reading fdroiddata needs none; the owner's push of the MR branch does.)
- `sums`: after the owner attaches a Mac-built IPA to the draft, recomputes and replaces `SHA256SUMS`.
- `tag`: prints the review diff and the exact `git tag -a` / `git push` commands for the owner.
- `fdroid-mr`: renders the MR branch in `~/Documents/LJ/fdroid/fdroiddata` (blocks + srclib) and runs `fdroid
  lint`/`rewritemeta` and drafts the MR text (incl. one line that the arm64 source tarball contains the AAR built
  from the srclib in prebuild); pushing and opening the MR are the owner's. While a frozen-recipe MR of ours is open, a newer
  release re-renders that MR to the newest tag instead of opening a second one (never !54838, which is D1's).
- Branches: `release/<ver>` is cut from `origin/main` after a fetch (the local `main` of some clones is
  months stale); `bump` is its first commit; after the owner publishes, the tagged commit goes to `main` — fast
  forward if `main` did not move, otherwise a merge commit (owner push) — and only then `post-publish` runs on
  `main`.
- Who runs what: everything that prompts for a password (`build.sh sign`) or writes to GitHub (`sums` replaces a
  release asset, pushes, tags) runs in the owner's terminal; agents run the read-only and local steps.
- `post-publish`: regenerates `website/altstore.json` (needs the published release and the checkout at the tag),
  commits it on main locally and prints the push command (owner step: AltStore users see the update only after it);
  lists issues mentioned in the changelog for replies.

### E. Rollout

1. Review and merge `build/ort-native-only`.
2. Implement A–D in logical commits on this branch; the owner merges it into `main` (so `release/<ver>` branches
   cut later carry `release.yml` and `tools/release/`).
3. Dry run: `release.py bump 0.9.4` on `release/0.9.4`, the owner pushes it: android, fdroid (all four blocks), ios; a
   `sign-dry` step with a key generated inside the job (no environment) runs the exact sign/verify/zipalign
   commands and asserts the certificate is *not* the pinned one. Also time the arm64 **ORT prebuild alone** in a
   1 CPU / 2 GB machine with disk-backed home (F8) — a real VM like the Vagrantfile's, or a container with
   `--cpuset-cpus=0` whose script reads the cgroup `memory.max` (`nproc` ignores `--cpus` quotas and
   `/proc/meminfo` shows host RAM) — with `build.sh fdroid-prebuild` choosing `JOBS` from the CPUs and memory it
   really has (ORT's LTO build at the script's default `JOBS=4` would OOM, not just run slowly); the Gradle part is judged by what production
   already built (0.9.0–0.9.2 are on f-droid.org, so production is larger than the Vagrantfile default and fits
   our Gradle settings). The result sets `timeout:` or triggers D4. CI cannot validate `timeout:` (enforced only
with `--server`, build.py:1189). Never push throwaway `v*` tags (they would
   run the release flow and, once the recipe is live, feed the F-Droid bot).
4. Owner creates environment `release`, the ruleset and the secrets (F).
5. 0.9.4: release branch pushed (dry run green) → `fdroid-mr` prepared → MR opened with the release commit hash
   **before** the tag, so its `fdroid build` job proves green; its `checkupdates` job (fdroiddata
   `.gitlab-ci.yml`:115–133, not allowed to fail) stays red until the tag exists, and the MR text says so → owner
   pushes the tag (alone: GitHub sends no push events when more than three tags are pushed at once) → owner
   retries only the MR's `checkupdates` job (the pipeline runs in his fork) → maintainer merges. The MR must not be merged before the tag: F-Droid would then publish a
   commit the GitHub release may never point at. Given
   review latency, a red bot MR for v0.9.4 is near-certain (F2; `push_commits` reopens it daily while its branch
   lacks the change) in the window between tag and merge. We say so in our MR and ask the maintainer to close the bot MR **keeping its branch** (or
   accept it reappearing) until ours merges; the same for the still-open !54923. Under D1(a), if !54838 merges after our MR is opened, ours is rebased.

### F. Owner-only steps

- Create environment `release` (required reviewer: himself, admin bypass off, tags `v*`); tag ruleset
  (create/update/delete).
- `base64 -i ~/Documents/LJ/keys/latentjam-release.p12 | gh secret set RELEASE_KEYSTORE_B64 --env release`,
  `gh secret set RELEASE_KEYSTORE_PASSWORD --env release` and `gh secret set RELEASE_KEY_PASSWORD --env release`
  (interactive prompts; the two may be equal).
- Phase 2: upload key, its passwords, Play service-account JSON.
- Renew the GitLab token; push branches/tags; approve signing; publish drafts; push the AltStore commit;
  retry the MR's `checkupdates` job after the tag; run `release.py sums` and `build.sh sign`.

## Risks

- R1. Mac, CI and F-Droid APKs are not byte-identical (LTO ORT with host paths, host-built protoc). Only same-host
  rebuilds are checked for identity (`reproducible_android.py`). SHA256SUMS describe the CI files only.
- R2. iOS Release on the macos-15 runner may not fit (E3) → Mac fallback.
- R3. F-Droid production may be smaller/slower than CI (F8); measured in E3.
- R4. F-Droid-side changes we cannot freeze: the new split build pipeline, network policy for prebuild downloads
  (ORT's dependency mirror), refreshed scanner rules. Then a bot MR goes red and we answer with an MR.
- R5. The reviewer may object to directory/glob scan entries or to Microsoft's prebuilt ORT for the three non-arm64
  ABIs (shipped that way since 0.9.0).

## Open decisions for the owner

- **D1 (0.9.3 on F-Droid).** (a) Apply linsui's `sed` to !54838 so 0.9.3 reaches F-Droid users now; it must be
  pushed, green and merged **before the v0.9.4 tag** (after that tag any pipeline on !54838 fails its
  checkupdates job); the 0.9.4 MR then switches to the frozen recipe. (b) Keep skipping 0.9.3: close !54838 and !54923, and the 0.9.4 MR adds the
  srclib too. Recommendation (a). Caveat for both: if the bot sees v0.9.4 before our MR merges, it copies the
  previous blocks and its MR fails (with (a) the 0.9.3 prebuild breaks on the new script) — harmless, closed by
  the maintainer.
- **D2.** Play upload from CI (phase 2) now or later.
- **D3.** A restricted GitHub token for agent sessions.
- **D5 (the debug keystore = release key).** (a) Accept and document: test builds keep installing as updates,
  any process of the owner's user can sign as LatentJam. (b) Replace `~/.android/debug.keystore` with a fresh
  key (the original is already backed up as the re-passworded `.p12`), so ordinary builds no longer carry the
  release identity; phone test builds that must install as updates are re-signed with `build.sh sign --resign`
  (explicit flag; apksigner replaces the debug signature; the owner types the password each time). Recommendation (b). Owner's call; agents do not touch keystores.
- **D4.** If the arm64 ORT build does not fit F-Droid's server: ship F-Droid arm64 with Maven Central's full ORT
  (bigger APK, as 0.9.2) instead of the reduced one.

## Review log

### Round 1 (two fresh reviewers: F-Droid side; CI/signing side) — NOT READY

Verified by me after the round: build.gradle.kts signing/splits lines, gh token scopes, Vagrantfile sizes,
build.py sudo/timeout lines, fdroiddata `.gitlab-ci.yml` build command.

Serious, fixed in v2:
- No unsigned switch: CI would sign with a random debug key, the Mac with the real key outside approval; AAB
  impossible without `keystore.properties` → `latentjam.unsigned` / `latentjam.bundle` (A, F10).
- AAB needs jarsigner, not apksigner; Play upload must not share the job with the keystore → C6.
- Approval is policy, not a barrier (agent token scopes, admin bypass default, workflow from tag) → C5, D3, goal 4.
- iOS Release on CI unproven → E3 measurement + Mac fallback (C3, R2).
- ORT work dir in `$TMPDIR` = tmpfs on trixie, 1 CPU/2 GB default VM, 7200 s timeout → disk-backed work dir,
  measurement, `timeout:`, D4.

Minor, fixed: apksigner key-pass/v4/verify/zipalign; dry run never exercised signing → `sign-dry`; no throwaway
tags; ORT cache key incomplete → no cache on CI, full `tools/runtime/` hash on Mac; pre-tag fdroid run needs a
pushed commit + commit-hash render; `--on-server` as root; config/srclib rendering; AltStore push as owner step;
versionCode sources; single release-notes source; rewritemeta field order; first-match crediting of scan entries;
moving tables is a big refactor → no move, `*.bin` glob primary; pins compared with fdroiddata master; tag-vs-MR
race; srclib file under D1(b).

Notes recorded as risks: R1 (no cross-host identity), R4 (split pipeline, network, scanner rules), R5, bot branch
pruning (F2), version-detection test (B check 4), build.py cleanup dirs (A fdroid-prebuild).

### Round 2 (one fresh reviewer, whole plan) — NOT READY

Verified by me: `git check-ignore` matches `tools/build/*` (`.gitignore:4 **/build/`); build.py:487–495 locks
root and purges sudo after every `--on-server` build; f-droid.org lists 0.9.0–0.9.2.

Serious, fixed in v3:
- The frozen entry point was in a git-ignored directory → everything under `tools/release/` (A, B).
- The `fdroid` job was not what fdroiddata CI does (sudo purge per build, `vagrant` user, bsenv, fetchsrclibs,
  safe.directory, disk) → matrix of four fresh containers running a port of fdroiddata's job (C2).

Minor, fixed: dispatch `ref` input vs `GITHUB_SHA` → no inputs, dispatch on the release branch, preflight matches
`headSha`; E3 measured the wrong thing → time only the ORT prebuild under 1 CPU / 2 GB, Gradle judged by
production history; bot MR is near-certain and deleting its branch makes it reappear → "close, keep branch";
check (3) compared only pins → whole blocks; notes could not reach CI → annotated tag message; Mac IPA fallback
ordering → `release.py sums` and explicit order; ruleset covers update/delete, bypass identity limit stated, D3
token Contents read-only; artifacts downloaded by id; approval diff covers the signed bytes' inputs; `unsigned` +
`playSigning` mutually exclusive.

Confirmed OK by the reviewer: `$HOME/.cache` on disk and outside the scan; `sdkmanager` in prebuild works on
production; the F4 cleanup leaves `build/reduced-ort/maven`; every scandelete dir holds a flagged file today and no
Gradle configuration reads them; scandelete removes only flagged files; unsigned APK names pass the existing gates;
apksigner 36 aligns libraries to 16 KiB by default.

### Round 3 (one fresh reviewer, whole plan) — NOT READY

Confirmed by the reviewer: NDK auto-install happens before prebuild with `ndk:` + `--on-server`; srclib Prepare runs
submodules; the bot's copy/zip/sudo logic; scan/glob/first-match rules; `build/reduced-ort/maven` survives cleanup;
`ort_source_build.sh` refuses a mismatched srclib tag; nothing under `tools/release/` is git-ignored.
Verified by me: lint.py:2291–2295 rejects a non-https git `Repo:`.

Serious, fixed in v4:
- `render --repo $GITHUB_WORKSPACE` fails `fdroid lint` → always the real https repo; the commit is pushed in both
  runs anyway (C2, B).
- No check that recipe pins equal the tree's ORT/NDK → check (5) (B), which is what makes goal 2 hold.

Minor, fixed: full port of fdroiddata's job (fdroidserver from pinned git, config/, JDK/SDK setup, gradlew-fdroid);
disk freed from a host job that `docker run`s the image; fixtures generated at test time; certificate digest
normalisation + negative test; draft downloads by id, top-level read-only permissions; tag message fetched
explicitly; E3 with `JOBS` sized to the machine; `timeout:` not CI-verifiable; D1(a) rebase; ruleset's real value
(blocks `GITHUB_TOKEN`).

### Round 4 (one fresh reviewer, three end-to-end scenarios) — NOT READY

Scenario results: (A) 0.9.4 blocked only by the dispatch problem below; (B) 0.9.5 with Kotlin changes and a new
`.bin` in assets/ml ships via the bot with no MR from us; (C) an ORT bump is stopped before the tag by check (5)
and by `ort_source_build.sh`, and the MR is minimal (arm64 srclib ref). Signing path: no leak found.
Verified by me: `~/.android/debug.keystore` is `-rw-r--r--` (the `.p12`/upload files are `-rw-------`).

Serious, fixed in v5:
- `workflow_dispatch` needs the workflow on the default branch → dry run triggered by pushing `release/**` (C).
- Goal 4 was false: the debug keystore is the release key, readable, default password → goal 4 restated, D5.

Minor, fixed: `check` on a `git archive` export with modelled prebuild output; "MR needed" semantics in preflight
(passes once `fdroid-mr` rendered it for this commit); split vs base versionCode comparison; CI asserts the tag
matches the APKs' versions and `sign` needs the fdroid matrix green; peeled commit hash; app-level bot fields in
check (3); production `.gitconfig` in the CI port; source-tarball line in the MR; no GitLab token in preflight;
one open MR re-rendered to the newest tag; MR-before-tag option preferred.

### Round 5 (one fresh reviewer, focus on the v5 changes) — NOT READY

Confirmed by the reviewer: branch/tag triggers and `if:`/`needs:` skipping behave as intended; scenario (B) ships
via the bot with no MR from us (glob per entry, Unused per entry, bot copies blocks verbatim, versionCode regex hits
line 60 first); `git archive` export is faithful (no .gitattributes/.gitmodules); "close, keep branch" works.
Verified by me: fdroiddata `.gitlab-ci.yml`:115–133 `checkupdates` job (`--auto` + `git diff --exit-code`).

Serious, fixed in v6:
- "Merge the MR before the tag" fails fdroiddata's `checkupdates` job and could publish an untagged commit →
  MR opened before the tag (build proves green), tag, re-run, merge (E5); the tag run also runs checkupdates (C2).

Minor, fixed: `sign-dry` as its own job; expected version per event; D5(b) test builds via `sign --resign`;
`concurrency`; `draft` needs with/without ios; peeled `GITHUB_SHA`; push the tag alone; same fdroidserver commit
for local `check` and CI.

### Round 6 (one fresh reviewer, whole plan read fresh) — NOT READY

Confirmed by the reviewer: bot copy/sort, fdroiddata's checkupdates job red before the tag and green after,
`build_flags` order, archive policy × len(VercodeOperation), clone reaches an untagged commit on a pushed branch,
scanner fatal entries, `timeout` only with `--server`, per-build sudo purge, pins, ort-native-only behaviour.

Serious, fixed in v7:
- The tag-run `checkupdates` step would first run on the real release, gate signing, and have no recovery path
  (tags cannot move) → removed from GitHub CI; fdroiddata's MR job retried after the tag is the proof (C2, E5).

Minor, fixed: rewritemeta gated by `git diff --exit-code`; template shows the arm64 block as 452 with the fixed
order; who runs `sums`/`sign` (owner's terminal) vs D3; frozen `build.sh` CLI tested; render takes a plain hash and
writes CurrentVersion/Code/AutoName into a committed work dir; CI never caches ORT; branch flow
`release/<ver>` → tag → main fast-forward → post-publish; only the checkupdates job is retried, by the owner;
`apksigner verify --verbose`; Play secret in a tag-only environment.

### Round 7 (one fresh reviewer, whole plan as implementer + 0.9.4 operator) — READY

No serious findings. Confirmed: build_flags order of the 452 template, bot copy/sudo pruning/tag→hash, fdroiddata's
checkupdates red before / green after the tag, prebuild runs as one `bash -e` line in androidApp with
substitutions, scan paths relative to the repo root, ort-native-only output path and in-tree refusal, non-arm64
F-Droid builds on Maven Central ORT, minSdk 24 → v2+v3 without v1, fdroiddata master metadata and !54838 blocks.

Minor, fixed in v8: fdroidserver follows master like fdroiddata (not pinned) and the commit is logged; rewritemeta
step list made consistent, render through `write_yaml`, canonical `10 * %c + 1`; D1(a) must merge before the
tag, re-render rule excludes !54838; tag-run fdroid jobs informational (gate = dry run on the same commit); E3
measures on a real 1 CPU / 2 GB (VM or cpuset + memory.max); A–D merged to main before release branches,
`bump` before the first dry run, merge commit when main moved, cut from `origin/main`; fastlane/audit checks in the
port and preflight; `--verify-tag` and no second draft; `--ks-key-alias release`; Gradle cache disabled in
release.yml.

## Implementation status (2026-11-01, branch claude/release-build-094-cd5256, not pushed)

Done: `build/ort-native-only` reviewed (MERGEABLE) and taken over, plus hardening of `ort_source_build.sh`;
Gradle switches `latentjam.unsigned` / `latentjam.bundle`; `tools/release/build.sh`; frozen recipe
`tools/release/fdroid/recipe.yml` + `fdroid_recipe.py` (render, check); `ci_fdroid_build.sh`;
`.github/workflows/release.yml`; `tools/release/release.py`; tests (`tools/release/test_*.py`, run by app.yml; the
fdroidserver-backed ones need `~/.cache/latentjam/fdroidserver-venv`). One adversarial code review: no serious
findings; its minor ones are fixed.

Verified locally:
- `build.sh android` (6 min, ORT from source): three unsigned APKs, arm64 with the reduced runtime; `bundle`: an
  unsigned AAB with all four ABIs; `ios`: an unsigned IPA (Xcode 27.2 on the Mac).
- Signing checks: the published 0.9.3 APK passes `verify-signed`; an APK signed with a throwaway key fails it.
- `fdroid_recipe.py check`: fdroidserver's scanner passes all four blocks on a `git archive` of HEAD; exit 3
  against fdroiddata master (MR needed: expected for 0.9.4).
- Real fdroidserver runs: `render` → `rewritemeta` changes nothing, `lint` passes; `checkupdates --auto` on a
  repository tagged v0.9.4/v0.9.5 produces exactly the blocks a fresh render for 0.9.5 gives (now a unit test);
  `fdroid build --test` of block 442 (arm64: prebuild builds ORT from the srclib, scanner deletes/ignores exactly
  the listed paths, no Unused/Non-exist) and of block 443 (x86) both succeed on the Mac.
- fdroiddata: draft branch `latentjam-0.9.4-draft` (provisional commit hash, lint clean), not pushed;
  `release.py fdroid-mr` re-renders it on the real release commit.

Not verified (needs the owner's push of a `release/0.9.4` branch): the GitHub workflow itself — the Linux
`android` job, the four `fdroid` containers (ci_fdroid_build.sh), the macOS `ios` job on Xcode 26.3, `sign-dry`,
and the E3 timing of the ORT prebuild on 1 CPU / 2 GB. The `sign` and `draft` jobs run first on the real tag.
