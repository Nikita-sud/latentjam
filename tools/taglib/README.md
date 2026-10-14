# Independent tag serialization checks

`python3 tools/taglib/verify.py` builds a **host-only** TagLib 2.3.2 reader, exports
synthetic files from the real Kotlin serializers, and checks the resulting metadata
with TagLib. JUnit 4 and the production codecs/writer remain in place. No TagLib
code, native binary or network dependency is added to the mobile applications.

The runner pins TagLib to `deadc2990767dfbda0701e0ab35fdeea653db08f` and its utf8cpp
submodule to `2d8e20b22dcb3e9b3c4f52103182ebda949c6089`. It verifies both checkouts
and builds against that utf8cpp even if another version is installed on the host.
Use `--source-dir /path/to/exact/checkout` to run without fetching sources. A JDK,
Android SDK, CMake, C++17 compiler and Python 3 are required. The upstream checkout
retains TagLib's LGPL/MPL and utf8cpp's license files; they are test dependencies,
not components of the distributed application SBOM.

Coverage includes MP3/ID3v2.3 and v2.4, FLAC, Opus, Vorbis and MP4: patch/rewrite
paths, Unicode, clearing fields, large lyrics, custom fields and ID3v1 migration.
Expected edited values come from the edit request, not our own parser. Untouched
properties are compared before/after through TagLib. The exporter additionally
runs our existing audio-digest, unknown-field inventory and write verification.

TagLib's MPEG `properties()` selects ID3v2 wholesale. The oracle explicitly reads
ID3v1 as a fallback for absent properties, so fields our writer migrates out of the
legacy trailer are checked rather than mistaken for new metadata. TagLib remains
an independent implementation, not a universal source of truth: a disagreement
must be investigated, not automatically worked around or used to change files.

Results are written to `build/reports/taglib.json`. CI runs this after host tests.
Fixture payloads are synthetic and are not intended for playback. Existing tests
with playable media, fuzzing and `DurableWriter` failure/recovery tests still cover
other parts of the file contract.
