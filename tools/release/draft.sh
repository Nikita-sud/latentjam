#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# The release workflow's draft step, also run by its branch dry run and by tools/release/test_draft.py:
#
#   draft.sh assets ASSETS ANDROID       the files to attach, flat in ASSETS: the artifacts downloaded there (the
#                                        IPA arrives one directory deeper, as <version>/LatentJam-…ipa), the SBOM
#                                        from ANDROID, and SHA256SUMS over them; fails on anything that is not a file
#   draft.sh notes TAG BASE IOS          the release notes: the tag's body (headings kept only by
#                                        `git tag --cleanup=verbatim`), then the changelog of BASE; IOS is the ios
#                                        job's result
#   draft.sh publish TAG VERSION ASSETS NOTES
#                                        a draft release for TAG with every file of ASSETS; a draft already there (a
#                                        re-run after a failed upload) gets the missing files and the notes instead;
#                                        a published release is never touched
set -euo pipefail

assets() {
    local dir=$1 android=$2
    find "$android" -name "*-sbom.cdx.json" -exec cp {} "$dir/" \;
    find "$dir" -mindepth 2 -type f -exec mv {} "$dir/" \;
    # The directories the files came from (v0.9.4's draft failed on "read assets/0.9.4: is a directory").
    find "$dir" -mindepth 1 -depth -type d -empty -delete
    local stray
    stray=$(find "$dir" -mindepth 1 ! -type f)
    if [ -n "$stray" ]; then
        echo "not a file: $stray" >&2
        return 1
    fi
    rm -f "$dir/SHA256SUMS"
    ls "$dir"/*.apk > /dev/null 2>&1 || { echo "no APK in $dir" >&2; return 1; }
    local sha=(sha256sum)
    command -v sha256sum > /dev/null || sha=(shasum -a 256)
    local sums
    # Every entry is a file now; an unreadable one fails here instead of vanishing behind a pipe.
    sums=$(cd "$dir" && "${sha[@]}" -- *)
    printf '%s\n' "$sums" | LC_ALL=C sort -k2 > "$dir/SHA256SUMS"
    echo "assets:"
    LC_ALL=C ls -1 "$dir"
}

notes() {
    local tag=$1 base=$2 ios=$3 body
    body=$(git tag -l --format='%(contents:body)' "$tag")
    if ! grep -q '^#' <<<"$body"; then
        # Default cleanup strips every line starting with '#': the tag was made without --cleanup=verbatim.
        echo "::warning::the notes of $tag have no headings; was it tagged with git tag --cleanup=verbatim?" >&2
    fi
    printf '%s\n' "$body"
    echo
    echo "### Changelog"
    cat "fastlane/metadata/android/en-US/changelogs/$base.txt"
    if [ "$ios" != success ]; then
        echo
        echo "<!-- iOS IPA not built in CI: attach it, then run tools/release/release.py sums -->"
    fi
}

publish() {
    local tag=$1 version=$2 dir=$3 notes_file=$4 state
    state=$(gh release view "$tag" --json isDraft --jq '.isDraft' 2>/dev/null || echo none)
    case $state in
        none)
            gh release create "$tag" --draft --verify-tag --title "LatentJam $version" --notes-file "$notes_file" \
                "$dir"/*
            ;;
        true)
            echo "a draft for $tag exists: uploading every file again and refreshing the notes"
            gh release upload "$tag" --clobber "$dir"/*
            gh release edit "$tag" --notes-file "$notes_file"
            ;;
        *)
            echo "$tag is already published; nothing is changed" >&2
            return 1
            ;;
    esac
}

command=${1:-}
shift || true
case $command in
    assets) assets "$@" ;;
    notes) notes "$@" ;;
    publish) publish "$@" ;;
    *) echo "usage: draft.sh assets|notes|publish ..." >&2; exit 2 ;;
esac
