#!/usr/bin/env python3
"""Compare APK footprint and verify unchanged assets/native libraries."""
import argparse
import hashlib
import json
from pathlib import Path
from zipfile import ZipFile


def inspect(path):
    groups = {name: {"raw": 0, "packed": 0} for name in ("models", "native", "dex", "other")}
    preserved, compilation_profiles = {}, {}
    with ZipFile(path) as package:
        for entry in package.infolist():
            name = entry.filename
            if name.endswith("/"):
                continue
            group = ("models" if name.endswith(".onnx") else "native" if name.endswith(".so")
                     else "dex" if name.endswith(".dex") else "other")
            groups[group]["raw"] += entry.file_size
            groups[group]["packed"] += entry.compress_size
            if name.startswith("assets/dexopt/"):
                compilation_profiles[name] = hashlib.sha256(package.read(name)).hexdigest()
            elif name.startswith("assets/") or name.endswith(".so"):
                preserved[name] = hashlib.sha256(package.read(name)).hexdigest()
    return {"apk_bytes": path.stat().st_size, "groups": groups, "preserved": preserved,
            "compilation_profiles": compilation_profiles}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", required=True, type=Path)
    parser.add_argument("--candidate", required=True, type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    before, after = inspect(args.baseline), inspect(args.candidate)
    names = set(before["preserved"]) | set(after["preserved"])
    changed = sorted(name for name in names if before["preserved"].get(name) != after["preserved"].get(name))
    profiles = set(before["compilation_profiles"]) | set(after["compilation_profiles"])
    changed_profiles = sorted(name for name in profiles
                              if before["compilation_profiles"].get(name) != after["compilation_profiles"].get(name))
    saved = before["apk_bytes"] - after["apk_bytes"]
    report = {"baseline": before, "candidate": after, "saved_bytes": saved,
              "saved_percent": round(saved * 100 / before["apk_bytes"], 3),
              "changed_assets_or_native": changed,
              "changed_compilation_profiles": changed_profiles,
              "note": "R8 regenerates ART profiles to match optimized DEX. All other assets/native libraries must match. Installed AppBytes is a separate measurement; this is not a functional test."}
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"baseline_bytes": before["apk_bytes"], "candidate_bytes": after["apk_bytes"],
                      "saved_bytes": saved, "saved_percent": report["saved_percent"],
                      "compared_assets_and_native": len(names), "changed_assets_or_native": changed,
                      "regenerated_compilation_profiles": changed_profiles}, ensure_ascii=False))
    if changed:
        raise SystemExit("Assets or native libraries changed; this APK does not meet the preservation check")


if __name__ == "__main__":
    main()
