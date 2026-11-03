#!/usr/bin/env python3
# Copyright (c) 2026 LatentJam Project
# SPDX-License-Identifier: Apache-2.0
"""The release checklist as commands (docs/release-build-094-plan.md, section D). State per version lives in
~/Documents/LJ/releases/<version>/state.json, so every step can be re-run.

  release.py bump VERSION        on release/VERSION, cut from origin/main: versions + changelog files
  release.py preflight           everything that must hold before the tag; exit 1 lists what does not
  release.py fdroid-mr           renders the fdroiddata MR branch locally (lint, rewritemeta); never pushes
  release.py tag                 prints the review diff and the owner's tag and push commands
  release.py sums                owner, after attaching a Mac-built IPA to the draft: replaces SHA256SUMS
  release.py post-publish        on main at the released commit: website/altstore.json, issue list

Agents run bump, preflight, fdroid-mr and tag. What writes to GitHub or asks for a password is the owner's.
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
APPID = "io.github.nikitasud.latentjam.kmp"
GRADLE = ROOT / "androidApp/build.gradle.kts"
PBXPROJ = ROOT / "iosApp/iosApp.xcodeproj/project.pbxproj"
CHANGELOGS = ROOT / "fastlane/metadata/android/en-US/changelogs"
RELEASES = Path.home() / "Documents/LJ/releases"
TEST_BUILDS = [Path.home() / "Documents/LJ/builds", RELEASES / "test-builds"]
FDROIDDATA = Path.home() / "Documents/LJ/fdroid/fdroiddata"
FDROID_PYTHON = Path.home() / ".cache/latentjam/fdroidserver-venv/bin/python"
SDK = Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk"))
CHANGELOG_LIMIT = 500  # F-Droid's whatsNew limit (fdroidserver update.py char_limits)
ABI_DIGITS = (1, 2, 3, 4)


def git(*args, cwd=ROOT, check=True):
    return subprocess.run(["git", *args], cwd=cwd, check=check, capture_output=True, text=True).stdout.strip()


def app_version(text=None):
    text = text if text is not None else GRADLE.read_text()
    name = re.search(r'^ *versionName = "([^"]+)"', text, re.M).group(1)
    code = int(re.search(r"^ *versionCode = (\d+)$", text, re.M).group(1))
    return name, code


def state_path(version):
    return RELEASES / version / "state.json"


def load_state(version):
    path = state_path(version)
    return json.loads(path.read_text()) if path.exists() else {}


def save_state(version, **changes):
    state = load_state(version) | changes
    state_path(version).parent.mkdir(parents=True, exist_ok=True)
    state_path(version).write_text(json.dumps(state, indent=2) + "\n")
    return state


def last_tag():
    return git("describe", "--tags", "--abbrev=0", "--match", "v[0-9]*", "HEAD")


def changelog_files(base):
    return [CHANGELOGS / f"{base}.txt"] + [CHANGELOGS / f"{base * 10 + d}.txt" for d in ABI_DIGITS]


# bump ---------------------------------------------------------------------------------------------------------------

def bump(version):
    branch = git("rev-parse", "--abbrev-ref", "HEAD")
    if branch != f"release/{version}":
        sys.exit(f"bump runs on release/{version} (git switch -c release/{version} origin/main), not {branch}")
    # From the last release, not the tree: a second bump of the same version (an interrupted session) changes nothing.
    _name, released = app_version(git("show", f"{last_tag()}:androidApp/build.gradle.kts"))
    new = released + 1
    text = GRADLE.read_text()
    text = re.sub(r'^( *versionName = )"[^"]+"', rf'\g<1>"{version}"', text, count=1, flags=re.M)
    text = re.sub(r"^( *versionCode = )\d+$", rf"\g<1>{new}", text, count=1, flags=re.M)
    GRADLE.write_text(text)
    project = PBXPROJ.read_text()
    project = re.sub(r"MARKETING_VERSION = [^;]+;", f"MARKETING_VERSION = {version};", project)
    project = re.sub(r"CURRENT_PROJECT_VERSION = \d+;", f"CURRENT_PROJECT_VERSION = {new};", project)
    PBXPROJ.write_text(project)
    base = CHANGELOGS / f"{new}.txt"
    if not base.exists():
        base.write_text("TODO: what changed, at most 500 characters; release.py preflight copies it to the four "
                        "ABI files.\n")
    print(f"{version} ({new}); write {base.relative_to(ROOT)}, then run preflight")


def sync_changelogs(base):
    """The base-code file is the single source; the four per-ABI files F-Droid reads are copies."""
    text = (CHANGELOGS / f"{base}.txt").read_text()
    for path in changelog_files(base)[1:]:
        path.write_text(text)


# preflight ----------------------------------------------------------------------------------------------------------

def apk_version_codes():
    """versionCode of every test APK on disk (aapt2 dump badging) and of the app on a connected phone."""
    aapt2 = next(iter(sorted(SDK.glob("build-tools/*/aapt2"), reverse=True)), None)
    codes = {}
    for directory in TEST_BUILDS:
        for apk in directory.rglob("*.apk") if directory.is_dir() and aapt2 else []:
            out = subprocess.run([str(aapt2), "dump", "badging", str(apk)], capture_output=True, text=True).stdout
            match = re.search(rf"package: name='{re.escape(APPID)}' versionCode='(\d+)'", out)
            if match:
                codes[str(apk)] = int(match.group(1))
    if shutil.which("adb"):
        out = subprocess.run(["adb", "shell", "dumpsys", "package", APPID], capture_output=True, text=True,
                             timeout=30).stdout
        for match in re.finditer(r"versionCode=(\d+)", out):
            codes["phone"] = max(codes.get("phone", 0), int(match.group(1)))
    return codes


def version_code_problems(base, codes, play_code=None):
    """Each new code must exceed what is installed anywhere: split codes (>= 100) against the new split codes,
    base codes (Play bundle, universal debug builds) against the new base."""
    problems = []
    lowest_split = base * 10 + ABI_DIGITS[0]
    for where, code in codes.items():
        if code >= 100 and code >= lowest_split:
            problems.append(f"{where} has versionCode {code}, not below the new split codes {lowest_split}+")
        if code < 100 and code >= base:
            problems.append(f"{where} has versionCode {code}, not below the new base {base}")
    if play_code is not None and play_code >= base:
        problems.append(f"Google Play already has versionCode {play_code}")
    return problems


def changelog_problems(base):
    files = changelog_files(base)
    missing = [p.name for p in files if not p.exists()]
    if missing:
        return [f"changelog files missing: {', '.join(missing)}"]
    texts = {p.read_text() for p in files}
    problems = []
    if len(texts) != 1:
        problems.append(f"the four ABI changelogs differ from {base}.txt (release.py preflight --sync-changelogs)")
    text = files[0].read_text()
    if "TODO" in text:
        problems.append(f"{base}.txt still has its TODO")
    if len(text.strip()) > CHANGELOG_LIMIT:
        problems.append(f"{base}.txt has {len(text.strip())} characters; F-Droid allows {CHANGELOG_LIMIT}")
    return problems


def dry_run_problems(version, head):
    """The release/<version> dry run on this exact commit: android, the four F-Droid blocks and sign-dry green, and the
    unit tests (app.yml) green."""
    out = subprocess.run(["gh", "run", "list", "--workflow", "release.yml", "--branch", f"release/{version}",
                          "--json", "databaseId,headSha,status", "--limit", "20"], capture_output=True, text=True)
    if out.returncode:
        return [f"gh run list failed: {out.stderr.strip()}"]
    runs = [r for r in json.loads(out.stdout) if r["headSha"] == head]
    if not runs:
        return [f"no release.yml dry run for {head[:10]}: push release/{version} (owner)"]
    jobs = json.loads(subprocess.run(["gh", "run", "view", str(runs[0]["databaseId"]), "--json", "jobs"],
                                     check=True, capture_output=True, text=True).stdout)["jobs"]
    results = {job["name"]: job["conclusion"] for job in jobs}
    needed = ["android", "sign-dry"] + [f"fdroid ({abi})" for abi in ("armeabi-v7a", "arm64-v8a", "x86", "x86_64")]
    problems = [f"dry-run job {name}: {results.get(name) or 'not finished'}" for name in needed
                if results.get(name) != "success"]
    # The unit tests (app.yml) on the same commit: the release jobs only build.
    # The push run of the commit itself, never a pull request's merge-commit run.
    tests = subprocess.run(["gh", "run", "list", "--workflow", "app.yml", "--branch", f"release/{version}",
                            "--event", "push", "--json", "headSha,status,conclusion", "--limit", "20"],
                           capture_output=True, text=True)
    if tests.returncode:
        problems.append(f"gh run list (app.yml) failed: {tests.stderr.strip()}")
    else:
        verified = [r for r in json.loads(tests.stdout) if r["headSha"] == head]
        if not verified:
            problems.append(f"no app.yml run for {head[:10]} on release/{version}")
        elif verified[0].get("conclusion") != "success":
            problems.append(f"app.yml (unit tests) for {head[:10]}: {verified[0].get('conclusion') or 'not finished'}")
    if results.get("ios") != "success":
        print(f"note: CI ios job {results.get('ios') or 'not finished'}; the IPA then comes from the Mac "
              f"(tools/release/build.sh ios, attach, release.py sums)")
    return problems


def preflight(args):
    version, base = app_version()
    head = git("rev-parse", "HEAD")
    state = load_state(version)
    problems = []
    if args.sync_changelogs:
        sync_changelogs(base)
    if git("status", "--porcelain"):
        problems.append("the working tree is not clean")
    branch = git("rev-parse", "--abbrev-ref", "HEAD")
    if branch != f"release/{version}":
        problems.append(f"on {branch}, not release/{version}")
    tag = last_tag()
    _old_name, old_code = app_version(git("show", f"{tag}:androidApp/build.gradle.kts"))
    if base <= old_code:
        problems.append(f"versionCode {base} is not above {tag}'s {old_code}")
    if f"MARKETING_VERSION = {version};" not in PBXPROJ.read_text() \
            or f"CURRENT_PROJECT_VERSION = {base};" not in PBXPROJ.read_text():
        problems.append("iOS MARKETING_VERSION / CURRENT_PROJECT_VERSION do not match (release.py bump)")
    problems += version_code_problems(base, apk_version_codes(), args.play_code or state.get("play_code"))
    if args.play_code:
        save_state(version, play_code=args.play_code)
    problems += changelog_problems(base)
    recipe = subprocess.run([str(FDROID_PYTHON), str(ROOT / "tools/release/fdroid_recipe.py"), "check"],
                            cwd=ROOT, capture_output=True, text=True)
    print(recipe.stdout.strip())
    if recipe.returncode == 3:
        if state.get("fdroid_mr_commit") != head:
            problems.append("this release needs an fdroiddata MR: run release.py fdroid-mr")
    elif recipe.returncode:
        problems.append("fdroid_recipe.py check failed (above)")
    remote = git("ls-remote", "origin", f"refs/heads/release/{version}").split("\t")[0]
    if remote != head:
        problems.append(f"release/{version} on GitHub is {remote[:10] or 'missing'}, not {head[:10]} (owner pushes)")
    else:
        problems += dry_run_problems(version, head)
    free = shutil.disk_usage(Path.home()).free / 1e9
    if free < 30:
        problems.append(f"only {free:.0f} GB free; keep at least 30")
    if subprocess.run(["gh", "auth", "status"], capture_output=True).returncode:
        problems.append("gh is not logged in")
    for problem in problems:
        print(f"NOT READY: {problem}")
    if not problems:
        save_state(version, preflight_commit=head)
        print(f"preflight OK for {version} ({base}) at {head[:10]}; next: release.py tag")
    return 1 if problems else 0


# fdroid-mr ----------------------------------------------------------------------------------------------------------

def fdroid_mr(_args):
    version, base = app_version()
    head = git("rev-parse", "HEAD")
    # The MR text says all four blocks were built by fdroidserver; that must already be true.
    problems = dry_run_problems(version, head)
    if problems:
        sys.exit("fdroid-mr needs a green dry run of this commit first:\n" + "\n".join(problems))
    if git("status", "--porcelain", "--untracked-files=no", cwd=FDROIDDATA):
        sys.exit(f"{FDROIDDATA} has uncommitted changes")
    git("fetch", "--quiet", "origin", "master", cwd=FDROIDDATA)
    branch = f"latentjam-{version}"
    # A re-render replaces this command's own commit; a commit made by hand (a reviewer's request) is never reset.
    exists = subprocess.run(["git", "rev-parse", "--verify", "--quiet", f"refs/heads/{branch}"], cwd=FDROIDDATA,
                            capture_output=True).returncode == 0
    if exists:
        subjects = git("log", "--format=%s", f"origin/master..refs/heads/{branch}", cwd=FDROIDDATA).splitlines()
        foreign = [x for x in subjects if not x.startswith(f"LatentJam {version} (")]
        if foreign:
            sys.exit(f"{FDROIDDATA}: {branch} has commits fdroid-mr did not make ({foreign[0]!r}); keep them by hand")
    git("switch", "--quiet", "-C", branch, "origin/master", cwd=FDROIDDATA)
    metadata = FDROIDDATA / "metadata" / f"{APPID}.yml"
    subprocess.run([str(FDROID_PYTHON), str(ROOT / "tools/release/fdroid_recipe.py"), "render", "--commit", head,
                    "--base", str(metadata), "--out", str(FDROIDDATA)], check=True)
    fdroid = str(FDROID_PYTHON.parent / "fdroid")
    subprocess.run([fdroid, "rewritemeta", APPID], cwd=FDROIDDATA, check=True, capture_output=True)
    subprocess.run([fdroid, "lint", APPID], cwd=FDROIDDATA, check=True)
    git("add", "metadata", "srclibs", cwd=FDROIDDATA)
    if subprocess.run(["git", "diff", "--cached", "--quiet"], cwd=FDROIDDATA).returncode == 0:
        sys.exit(f"{branch}: nothing to commit (already rendered?)")
    git("commit", "--quiet", "-m", f"LatentJam {version} ({base * 10 + 1}-{base * 10 + 4}): frozen per-ABI recipe",
        cwd=FDROIDDATA)
    text = MR_TEXT.format(version=version, first=base * 10 + 1, last=base * 10 + 4, commit=head)
    (RELEASES / version).mkdir(parents=True, exist_ok=True)
    (RELEASES / version / "fdroid-mr.md").write_text(text)
    save_state(version, fdroid_mr_commit=head, fdroid_mr_branch=branch)
    print(f"{FDROIDDATA} branch {branch} ready; MR text in {RELEASES / version / 'fdroid-mr.md'}.\n"
          f"Owner: git -C {FDROIDDATA} push fork {branch}, then open the MR before the tag.")


MR_TEXT = """LatentJam {version} ({first}-{last}), commit {commit}

The four per-ABI build blocks no longer change from one release to the next, so checkupdates can ship later tags
on its own (it copies the last four blocks verbatim; VercodeOperation 10 * %c + 1..4):

- prebuild is one line calling `tools/release/build.sh fdroid-prebuild <abi>` in the app's repository; for arm64-v8a
  it builds the reduced ONNX Runtime from the `onnxruntime` srclib (native build only, nothing in the srclib is
  edited) into `build/reduced-ort/maven`, which is scanignored. That built AAR is also in the source tarball.
- scanignore `androidApp/src/main/assets/ml/*.bin`: data only (packed indices and tables; provenance and licences
  in `androidApp/src/main/assets/ml/LICENSE-MODEL.txt`). Some of these files are not packaged at all
  (`ignoreAssetsPattern` in androidApp/build.gradle.kts); host tests read them in place.
- scandelete names whole test/fixture directories: `tools/fuzz/corpus`, `core/smart/src/commonTest/resources`,
  `core/smart/src/androidHostTest/resources`, `iosApp/iosApp/ml` (the iOS copy of the data).
- The other three ABIs use Maven Central's onnxruntime-android, as every earlier version did.

This commit is not tagged yet: the `checkupdates` job stays red until v{version} is pushed; I will retry it then.
Please do not merge before that. If the bot opens its own MR for v{version} meanwhile, it can be closed in favour of
this one.

- [x] fdroid lint and rewritemeta pass
- [x] all four blocks built with `fdroid build --on-server` in fdroiddata's buildserver image (upstream CI)
"""


# tag ----------------------------------------------------------------------------------------------------------------

def tag(_args):
    version, _base = app_version()
    head = git("rev-parse", "HEAD")
    if load_state(version).get("preflight_commit") != head:
        sys.exit("run release.py preflight on this commit first")
    previous = last_tag()
    print(f"== Everything since {previous} that ends up in the signed files:\n")
    print(git("diff", "--stat", f"{previous}..HEAD"))
    print("\n== What handles the signing key (read before approving the release environment):\n")
    print(git("diff", f"{previous}..HEAD", "--", ".github/", "tools/release/"))
    notes = RELEASES / version / "tag-message.md"
    print(f"\n== Owner: write {notes}: first line 'LatentJam {version}', a blank line, then the notes, starting with\n"
          f"a one-paragraph summary (the GitHub release body and AltStore's description start there). Then:\n"
          f"git tag -a v{version} -F {notes} {head}\n"
          f"git push origin v{version}\n"
          "Push this tag alone (GitHub sends no push events for more than three tags at once).")


# sums ---------------------------------------------------------------------------------------------------------------

def sums(_args):
    version, _base = app_version()
    # Only after CI's ios job failed and the IPA was built on the Mac: two IPAs break the AltStore source.
    if not sys.stdin.isatty() or input(f"Replace SHA256SUMS of the v{version} draft on GitHub? [yes] ") != "yes":
        sys.exit("not confirmed")
    with tempfile.TemporaryDirectory() as tmp:
        subprocess.run(["gh", "release", "download", f"v{version}", "--dir", tmp], check=True)
        lines = []
        for path in sorted(Path(tmp).iterdir()):
            if path.name != "SHA256SUMS":
                lines.append(f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}")
        Path(tmp, "SHA256SUMS").write_text("\n".join(lines) + "\n")
        subprocess.run(["gh", "release", "upload", f"v{version}", str(Path(tmp, "SHA256SUMS")), "--clobber"],
                       check=True)
        print("\n".join(lines))


# post-publish -------------------------------------------------------------------------------------------------------

def post_publish(_args):
    version, base = app_version()
    if git("rev-parse", "--abbrev-ref", "HEAD") != "main":
        sys.exit("run on main after the tagged commit reached it")
    release = json.loads(subprocess.run(["gh", "release", "view", f"v{version}", "--json", "isDraft,assets"],
                                        check=True, capture_output=True, text=True).stdout)
    if release["isDraft"]:
        sys.exit(f"v{version} is still a draft: publish it first")
    if not any(a["name"].endswith(".ipa") for a in release["assets"]):
        sys.exit(f"v{version} has no IPA: attach it and run release.py sums first")
    subprocess.run([sys.executable, str(ROOT / "tools/release/altstore-source.py")], check=True)
    git("add", "website/altstore.json")
    if git("diff", "--cached", "--name-only"):
        git("commit", "--quiet", "-m", f"AltStore source: LatentJam {version}")
    print("Owner: git push origin main   (AltStore users see the update after this push)")
    issues = sorted(set(re.findall(r"#(\d+)", (CHANGELOGS / f"{base}.txt").read_text())), key=int)
    if issues:
        print("Issues named in the changelog, to answer: " + ", ".join(f"#{n}" for n in issues))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    b = sub.add_parser("bump")
    b.add_argument("version")
    p = sub.add_parser("preflight")
    p.add_argument("--play-code", type=int, help="the highest versionCode on Google Play (remembered)")
    p.add_argument("--sync-changelogs", action="store_true", help="copy <base>.txt to the four ABI files first")
    for name in ("fdroid-mr", "tag", "sums", "post-publish"):
        sub.add_parser(name)
    args = parser.parse_args(argv)
    if args.command == "bump":
        return bump(args.version)
    return {"preflight": preflight, "fdroid-mr": fdroid_mr, "tag": tag, "sums": sums,
            "post-publish": post_publish}[args.command](args)


if __name__ == "__main__":
    sys.exit(main())
