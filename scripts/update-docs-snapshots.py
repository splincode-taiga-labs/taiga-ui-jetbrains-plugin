#!/usr/bin/env python3

"""Update the checked-in offline snapshots from an immutable Taiga UI Pages revision."""

import argparse
from datetime import date
import gzip
from hashlib import sha256
from io import BytesIO
from pathlib import Path
import re
from urllib.request import urlopen

MAX_BYTES = 4 * 1024 * 1024
ROOT = Path(__file__).resolve().parents[1] / "src/main/resources/taiga-docs"
UPSTREAM = "https://raw.githubusercontent.com/taiga-family/taiga-ui"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("revision", help="Full commit SHA from the upstream gh-pages branch")
    parser.add_argument("--retrieved", type=date.fromisoformat, default=date.today())
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9a-f]{40}", args.revision):
        parser.error("revision must be a full Git commit SHA")

    archives = {}
    manifest = ["schema=1", f"revision={args.revision}", f"retrieved={args.retrieved.isoformat()}"]
    for major, path in [(4, "v4/llms-full.txt"), (5, "llms-full.txt")]:
        url = f"{UPSTREAM}/{args.revision}/{path}"
        with urlopen(url, timeout=30) as response:
            content = response.read(MAX_BYTES + 1)
        if len(content) > MAX_BYTES or not content.startswith(b"# Import Map - Package Exports Reference"):
            raise ValueError(f"Unexpected documentation format or size at {url}")
        output = BytesIO()
        with gzip.GzipFile(fileobj=output, mode="wb", compresslevel=9, mtime=0) as archive:
            archive.write(content)
        archives[major] = output.getvalue()
        manifest.extend([f"v{major}.source={url}", f"v{major}.sha256={sha256(content).hexdigest()}"])

    for major, archive in archives.items():
        (ROOT / f"v{major}.txt.gz").write_bytes(archive)
    (ROOT / "manifest.properties").write_text("\n".join(manifest) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
