"""Audit the named working files, or the exact Git index blobs, before publication."""
import argparse
import hashlib
import re
import subprocess
import sys
from pathlib import Path, PurePosixPath


LIMIT = 100_000_000
REVIEWED_BINARY = {
    "app/libs/paddleocr-textline.jar": "d76f6a628f78e4a39830e317410a73ab376245fb5d8625bf82783eb09dacb9dd",
    "gradle/wrapper/gradle-wrapper.jar": "81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f",
    "app/src/main/assets/ocr/ppocrv5-mobile-det.onnx": "a431985659dc921974177a95adcfbb90fd9e51989a5e04d70d0b75f597b6e61d",
    "app/src/main/assets/ocr/ppocrv5-mobile-rec.onnx": "da72dc72ca4dc220df0dfde68c1dedc31c58d3e76a25871122e5056227d50092",
    "app/src/main/assets/ocr/ppocrv5-server-rec.onnx": "d9dc333c9c7b042c6dffb8e33d72b6f65c9c1d463d0a3c2f78174fea55e94752",
    "app/src/androidTest/assets/flat-ledger.png": "cbaafd1ca0665bb6b7bc40fbbba20368f1d03b3bba8e17fc8ce56368d9a02eff",
    "app/src/androidTest/assets/printed-table.jpg": "77c587da555fd2dd01f308cb49b3dd6ebcd98a04fe64259b3e8b6201dd99b0b6",
    "app/src/main/res/drawable-nodpi/yeguang_launcher_art.png": "539c2bf227c6ed00569a8b585573bacd384fc68ca2898991f7348bb6c8a1745f",
    "design/yeguang-app-icon.png": "59be67a127eb57ca753a354f91142e9b192af1746cacc00b77e2ab38f64a7a1f",
    "docs/images/table-editor.png": "fabc6eba1360bce7d0826ba16cc3d512d559beece7a5df0d01fb582ed1fafb6f",
}
PRIVATE_PARTS = {".git", ".gradle", ".idea", "build", "release", "out", "__pycache__", "holdout"}
PRIVATE_NAMES = {"local.properties", ".DS_Store", "PLAN.md", "验收报告.md", "夸克扫描王调研与首版范围.md",
                 "android-editor-reference.png"}
PRIVATE_SUFFIXES = {".keystore", ".jks", ".key", ".p12", ".pfx", ".pem", ".apk", ".aab", ".apks",
                   ".zip", ".log", ".pdf", ".xlsx", ".docx", ".pyc", ".sqlite", ".db"}
SENSITIVE_TEXT = {
    "personal filesystem path": re.compile(r"(?:/(?:Users|home)/[\w.-]+/|/var/folders/[\w]+/|[a-z]:[\\/]users[\\/][\w.-]+[\\/])", re.I),
    "clipboard attachment or private evidence path": re.compile(r"codex-clipboard-[0-9a-f]{8}-|build/rc\d+-private(?:[/\\]|\b)", re.I),
    "private key": re.compile(r"-----BEGIN (?:[A-Z0-9 ]+ )?PRIVATE KEY-----"),
    "access credential": re.compile(r"(?:gh[pousr]_[A-Za-z0-9]{32,}|github_pat_[A-Za-z0-9_]{45,}|AKIA[A-Z0-9]{16}|sk-(?:proj-)?[A-Za-z0-9_-]{30,}|AIza[\w-]{35})"),
    "hardcoded credential": re.compile(r"""\b(?:api[_-]?key|access[_-]?token|client[_-]?secret|storePassword|keyPassword)\s*[:=]\s*["'][\w.+/=:-]{12,}["']""", re.I),
    "embedded image data": re.compile(r"data:image/[^;]+;base64,[A-Za-z0-9+/]{32,}", re.I),
    "possible mobile phone number": re.compile(r"(?<![A-Za-z0-9])(?:\+?86[ -]?)?1[3-9](?:[ -]?[0-9]){9}(?![A-Za-z0-9])"),
}


def permitted_path(name):
    path = PurePosixPath(name)
    return (bool(name) and str(path) == name and not path.is_absolute() and ".." not in path.parts
            and "\\" not in name and all(ord(char) >= 32 for char in name)
            and not PRIVATE_PARTS.intersection(path.parts) and not path.name.startswith(".env")
            and not path.name.startswith("codex-clipboard-") and path.name not in PRIVATE_NAMES
            and path.suffix.lower() not in PRIVATE_SUFFIXES
            and name != "tools/qa/handwriting/samples.json")


def inspect(name, data):
    digest = REVIEWED_BINARY.get(name)
    if digest is not None:
        if hashlib.sha256(data).hexdigest() != digest:
            yield "changed binary needs content review"
        return
    if PurePosixPath(name).suffix.lower() in {".png", ".jpg", ".jpeg", ".webp", ".gif", ".jar", ".onnx"}:
        yield "unreviewed image or binary"
        return
    try:
        content = data.decode("utf-8")
        if "\x00" in content:
            raise ValueError("binary")
    except ValueError:
        yield "unreviewed binary"
        return
    for i, line in enumerate(content.splitlines(), 1):
        for label, pattern in SENSITIVE_TEXT.items():
            if pattern.search(line):
                yield f"{i}: {label}"


def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args])


def entries(options, root):
    if options.manifest:
        for name in options.manifest.read_text(encoding="utf-8").splitlines():
            yield name, None, None
    else:
        for entry in git(root, "ls-files", "--stage", "-z").split(b"\0"):
            if entry:
                info, encoded = entry.split(b"\t", 1)
                mode, oid, stage = info.decode("ascii").split()
                if stage != "0":
                    raise ValueError("unmerged index")
                yield encoded.decode("utf-8"), mode, oid


def check(options):
    root = options.root.resolve()
    seen = set()
    failures = 0
    for name, mode, oid in entries(options, root):
        try:
            if not permitted_path(name):
                raise ValueError("excluded path")
            if name in seen:
                raise ValueError("duplicate path")
            if mode is not None and mode not in {"100644", "100755"}:
                raise ValueError("unsupported index mode")
            seen.add(name)
            path = root / name
            if oid:
                size = int(git(root, "cat-file", "-s", oid))
            else:
                if path.resolve() != path or not path.is_file():
                    raise ValueError("missing file or symlink")
                size = path.stat().st_size
            if size > LIMIT:
                raise ValueError("exceeds 100 MB")
            data = git(root, "cat-file", "blob", oid) if oid else path.read_bytes()
            for problem in inspect(name, data):
                print(f"{name}: {problem}", file=sys.stderr)
                failures += 1
        except (OSError, ValueError, subprocess.CalledProcessError) as err:
            print(f"{name}: {err}", file=sys.stderr)
            failures += 1
    if not seen:
        raise ValueError("no candidate files to check")
    print(f"{len(seen)} candidate files, {failures} finding(s)")
    return int(bool(failures))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    modes = parser.add_mutually_exclusive_group(required=True)
    modes.add_argument("--manifest", type=Path, help="UTF-8 file with one repository-relative path per line")
    modes.add_argument("--staged", action="store_true", help="check Git's staged snapshot")
    try:
        sys.exit(check(parser.parse_args()))
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        sys.exit(str(error))
