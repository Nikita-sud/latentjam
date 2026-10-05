"""Streams the public evaluation audio (MPD previews, FMA test clips) as a tar under the manifests' remote names.
The owner's library (manifests/listener.json) is never included. Run it from the bench folder (TEACHER_BENCH)."""
import json
import sys
import tarfile

seen = set()
with tarfile.open(fileobj=sys.stdout.buffer, mode="w|") as tar:
    for lib in sys.argv[1:]:
        assert lib != "listener"
        for row in json.load(open(f"manifests/{lib}.json")):
            if row["remote"] and row["remote"] not in seen:
                seen.add(row["remote"])
                tar.add(row["path"], arcname=row["remote"])
print(f"{len(seen)} files", file=sys.stderr)
