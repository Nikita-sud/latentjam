#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# One F-Droid build block of this commit, built the way fdroiddata's own `fdroid build` CI job builds an MR
# (https://gitlab.com/fdroid/fdroiddata/-/blob/master/.gitlab-ci.yml, job "fdroid build"). Runs as root inside
#   registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie
# with this repository mounted read-only at /src (docs/release-build-094-plan.md, section C2).
#
#   ci_fdroid_build.sh ABI COMMIT
#
# COMMIT must be pushed to github.com/Nikita-sud/latentjam: F-Droid clones the real repository.
set -euo pipefail
ABI=$1 COMMIT=$2
SRC=${LATENTJAM_SRC:-/src}
APPID=io.github.nikitasud.latentjam.kmp
case "$ABI" in armeabi-v7a) DIGIT=1;; arm64-v8a) DIGIT=2;; x86) DIGIT=3;; x86_64) DIGIT=4;; *) exit 2;; esac
set -x

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get -y dist-upgrade
# shellcheck disable=SC1091
source /etc/profile.d/bsenv.sh                 # home_vagrant, ANDROID_HOME, gradlew-fdroid on PATH
export ANDROID_HOME=/opt/android-sdk
: "${home_vagrant:?bsenv.sh did not set home_vagrant}"
sdkmanager "platform-tools" "build-tools;31.0.0"

# fdroidserver from git master, as fdroiddata installs it for every MR and bot pipeline.
fdroidserver=/opt/fdroidserver-git
git clone --quiet --shallow-since=2026-07-13 https://gitlab.com/fdroid/fdroidserver.git $fdroidserver
git -C $fdroidserver config transfer.fsckObjects true
echo "fdroidserver $(git -C $fdroidserver rev-parse HEAD)"
export PATH="$fdroidserver:$PATH" PYTHONPATH="$fdroidserver:$fdroidserver/examples" PYTHONUNBUFFERED=true
export serverwebroot=/tmp
git -C "$home_vagrant/gradlew-fdroid" pull

# The production buildserver's git configuration.
git -C /tmp clone --quiet https://gitlab.com/fdroid/fdroid-bootstrap-buildserver.git
git -C /tmp/fdroid-bootstrap-buildserver config transfer.fsckObjects true

# An fdroiddata work dir: its config, tools and .gitignore, plus the app's current metadata from master.
DATA=/fdroiddata
git clone --quiet --depth 1 --filter=blob:none --sparse https://gitlab.com/fdroid/fdroiddata.git $DATA
git -C $DATA sparse-checkout set --no-cone /config/ /tools/ /srclibs/onnxruntime.yml /.gitignore \
  "/metadata/$APPID.yml"
cp /tmp/fdroid-bootstrap-buildserver/roles/production_hardening/files/gitconfig $DATA/../.gitconfig
cd $DATA
git config user.email ci@localhost && git config user.name ci
python3 "$SRC/tools/release/fdroid_recipe.py" render --commit "$COMMIT" --base "metadata/$APPID.yml" --out .
git add -A && git commit -qm "LatentJam $COMMIT" --allow-empty
CODE=$(sed -n 's/^CurrentVersionCode: *\([0-9]*\)$/\1/p' "metadata/$APPID.yml")
CODE=$((CODE - 4 + DIGIT))

fdroid lint "$APPID"
fdroid rewritemeta "$APPID"
git diff --exit-code                         # rewritemeta --list alone always exits 0

for d in logs tmp unsigned "$home_vagrant/.android" "$home_vagrant/.gradle" "$home_vagrant/metadata"; do
  mkdir -p "$d"; chown -R vagrant "$d"
done
ln -s "$home_vagrant/.gradle" $DATA/.gradle
ln -s $DATA/tmp "$home_vagrant/tmp"
ln -s $DATA/srclibs "$home_vagrant/srclibs"
sysctl fs.inotify.max_user_watches=524288 || true
export GRADLE_USER_HOME=$home_vagrant/.gradle
fdroid="sudo --preserve-env --user vagrant env PATH=$fdroidserver:$PATH env PYTHONPATH=$fdroidserver:$fdroidserver/examples
  env PYTHONUNBUFFERED=true env TERM=${TERM:-dumb} env HOME=$home_vagrant fdroid"

apt-get install -y sudo
apt-get install -y openjdk-21-jdk-headless
update-alternatives --set java /usr/lib/jvm/java-21-openjdk-amd64/bin/java
mkdir -p build && cp -R build "$home_vagrant/build"
cp -R "metadata/$APPID.yml" "$home_vagrant/metadata"
chown -R vagrant "$home_vagrant" $DATA
pushd "$home_vagrant"
ln -s $DATA "$home_vagrant/fdroiddata"
ln -s $DATA/../.gitconfig "$home_vagrant/.gitconfig"
$fdroid fetchsrclibs "$APPID:$CODE" --verbose
rm "$home_vagrant/fdroiddata" "$home_vagrant/.gitconfig"
(unset CI; $fdroid build --verbose --test --refresh-scanner --on-server --no-tarball "$APPID:$CODE")
popd
python3 tools/audit-gradle.py "$APPID"
ls -l "$DATA/tmp/${APPID}_${CODE}.apk"
