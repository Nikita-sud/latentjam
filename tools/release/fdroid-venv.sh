#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# The Python that fdroid_recipe.py and `release.py fdroid-mr` run under, as fdroiddata's CI has it: fdroidserver from
# git master on Debian trixie's python3-ruamel.yaml (0.18.10+ds-1). Two ruamel versions wrap long scalars differently
# (0.19 split a prebuild line where 0.18 does not), so a file rendered under another one fails fdroiddata's
# "fdroid rewritemeta" job (MR !55889). fdroid_recipe.py refuses any other version (CI_RUAMEL_YAML).
#
#   tools/release/fdroid-venv.sh [VENV]      default ~/.cache/latentjam/fdroidserver-venv; recreated from scratch
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
venv=${1:-$HOME/.cache/latentjam/fdroidserver-venv}
ruamel=$(sed -n 's/^CI_RUAMEL_YAML = "\(.*\)".*/\1/p' "$here/fdroid_recipe.py")
[ -n "$ruamel" ] || { echo "CI_RUAMEL_YAML not found in fdroid_recipe.py" >&2; exit 1; }
python=${PYTHON:-python3}
rm -rf "$venv"
"$python" -m venv "$venv"
"$venv/bin/pip" install --quiet --upgrade pip
"$venv/bin/pip" install --quiet "git+https://gitlab.com/fdroid/fdroidserver.git" "ruamel.yaml==$ruamel"
"$venv/bin/python" -c "import ruamel.yaml, fdroidserver; print('fdroidserver venv ready, ruamel.yaml', ruamel.yaml.__version__)"
