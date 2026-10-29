#!/usr/bin/env python3
"""Fetch the official, pinned Go archive into THIS project; no global installation."""
import hashlib
import json
import pathlib
import platform
import tarfile
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
VERSION = "go1.27.3"
FILENAME = VERSION + ".darwin-arm64.tar.gz"
SHA256 = "dd3908cf20959d798c1e06ebe5b72213f0505ce01373b40ce3110975a6cd99c2"

def main():
    target = ROOT / ".tools"
    target.mkdir(exist_ok=True)
    binary = target / "go/bin/go"
    if binary.exists():
        print("Project-local Go already available.")
        return
    if platform.system() != "Darwin" or platform.machine() != "arm64":
        raise SystemExit("Use an existing Go >=1.27.3 via GO_BIN on this platform.")
    archive = target / FILENAME
    if not archive.exists():
        request = urllib.request.Request("https://go.dev/dl/" + FILENAME,
                                         headers={"User-Agent": "NodeMonitor-local-build"})
        with urllib.request.urlopen(request, timeout=90) as response, archive.open("wb") as out:
            while chunk := response.read(1024 * 1024):
                out.write(chunk)
    digest = hashlib.file_digest(archive.open("rb"), "sha256").hexdigest()
    if digest != SHA256:
        archive.unlink(missing_ok=True)
        raise SystemExit("Official Go checksum mismatch; nothing was extracted.")
    with tarfile.open(archive) as bundle:
        bundle.extractall(target, filter="data")
    (target / "go-provenance.json").write_text(json.dumps({
        "version": VERSION, "url": "https://go.dev/dl/" + FILENAME,
        "sha256": SHA256, "checksumSource": "https://go.dev/dl/?mode=json",
        "scope": "project-local build tool only"
    }, indent=2) + "\n")
    archive.unlink()
    print("Verified official Go extracted inside .tools/go; no system installation.")

if __name__ == "__main__":
    main()
