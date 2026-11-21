#!/usr/bin/env python3
"""Fetch and freeze the official PubTabNet examples for local table regressions.

Only URLs, filenames and checksums are distributed with this script. Images,
original annotations and derived truth are written to the requested local path.
Python 3.10+; standard library only.
"""
import argparse
import hashlib
from html.parser import HTMLParser
import json
from pathlib import Path
import urllib.request

REVISION = "8ffde9024bd331f5a61c3c549fdf30dd3c3e43a5"
BASE_URL = f"https://raw.githubusercontent.com/ibm-aur-nlp/PubTabNet/{REVISION}/examples/"
ANNOTATION_FILE = "PubTabNet_Examples.jsonl"
ANNOTATION_SHA256 = "858ad438bdc1fd6af7538e7a0704c85d62696a601dadf0174a5b0b380685e9f2"
IMAGE_SHA256 = {
    "PMC4840965_004_00.png": "eb468f05a4713a74ec9c0faa9f1358284b533e8c64c62766368dc20c75695415",
    "PMC4517499_004_00.png": "d1efce1913c7f4d4e37981823fd1aa180ca40827d352d12a458181c3be5bd406",
    "PMC4776821_005_00.png": "6e784272fdb31d9735646735f680a392c6cf4fe72ed3fa2c92a5acc023ba3185",
    "PMC1626454_002_00.png": "d34a3a312e57f9c2f3aba335729320ee3dd52ddf09b0c81b59d65746d910dac7",
    "PMC2838834_005_00.png": "bc562e84633962e9d0dcdbdecf0169980b42df376d350007520a788c43a57b80",
    "PMC5897438_004_00.png": "f877407e24a4e266252c12271007e22499f5894d5d2a22d53e43f7472245e3ee",
    "PMC3907710_006_00.png": "339451c58c0ddf8c49038cbd581dd9fc203f068fd965fe14e6c7a95128042fdd",
    "PMC3519711_003_00.png": "3d757f0856ae2f9adb55aff938b005e61db8a3decd62c8d8606cebf856c16a90",
    "PMC5198506_004_00.png": "657d1d4fa5238fdc0e7709670ef772efc4801321423618232eb7613a8a1de705",
    "PMC5679144_002_01.png": "fc12c6ab89ea185608406824241d089174acfe876374c95c48d12830aa9a1019",
    "PMC5134617_013_00.png": "d50fbaef4ffcc18fcf11debe4b958acc29736294f89e3a686cb633436a046d37",
    "PMC2753619_002_00.png": "3edcd682c7817fe3aab1bf531a5eff24a66e5e489a389d3e210e5fe5eaf48d51",
    "PMC3826085_003_00.png": "9e4858aea6b319dd999a5b3cb2da53891623df921899acbc50ce07d289e55f57",
    "PMC5577841_001_00.png": "d26c655f49b5a1fc3a2ce4cd7cfa5a10899ea6ccb6038945521eb42853d2ec1d",
    "PMC2759935_007_01.png": "889342e1d4efb0d028fc35602bcfae92c4e6eee6553709b8fba172adf10657ed",
    "PMC4003957_018_00.png": "23aa73eafce9b7fa86955debdc27d4ef08e6ecd120f00b901a6c35e86ea39e05",
    "PMC4682394_003_00.png": "fb05a92e9aeae9f90eb08cdd6ff72f1fbf0fc7cd1e5274a76e522e16284ab741",
    "PMC4172848_007_00.png": "d15370b68ac5922badb07117a85e681047efc3d09c4a7d88d30c4e6fda5dd961",
    "PMC5332562_005_00.png": "cbd871f11347b63187007b89d1d6fe6450f86ebd7aaafbe52c3de64e50199937",
    "PMC5402779_004_00.png": "51d6cfbc8c933fb110bbd4236518b9638a2f0c32bb2aff7e1045ae1ad36a4d88",
}


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def verified_bytes(path, expected):
    data = path.read_bytes()
    if sha256(data) != expected:
        raise ValueError(f"Checksum mismatch: {path.name}")
    return data


def fetch_verified(url, destination, expected):
    if destination.exists():
        return verified_bytes(destination, expected)
    request = urllib.request.Request(url, headers={"User-Agent": "yeguang-table-qa"})
    with urllib.request.urlopen(request, timeout=60) as response:
        data = response.read()
    if sha256(data) != expected:
        raise ValueError(f"Upstream checksum mismatch: {destination.name}")
    temporary = destination.with_suffix(destination.suffix + ".part")
    temporary.write_bytes(data)
    temporary.replace(destination)
    return data


class LogicalGrid(HTMLParser):
    """Turn the upstream HTML spans into logical coordinates, without OCR or model predictions."""

    def __init__(self):
        super().__init__()
        self.row = -1
        self.column = 0
        self.columns = 0
        self.cells = []
        self.occupied = set()

    def handle_starttag(self, tag, attributes):
        if tag == "tr":
            self.row += 1
            self.column = 0
        if tag not in ("td", "th"):
            return
        if self.row < 0:
            raise ValueError("Cell outside a table row")
        while (self.row, self.column) in self.occupied:
            self.column += 1
        attributes = dict(attributes)
        height = int(attributes.get("rowspan", "1"))
        width = int(attributes.get("colspan", "1"))
        if height < 1 or width < 1:
            raise ValueError("Invalid source span")
        self.cells.append([self.row, self.column, height, width])
        for row in range(self.row, self.row + height):
            for column in range(self.column, self.column + width):
                if (row, column) in self.occupied:
                    raise ValueError("Overlapping source cells")
                self.occupied.add((row, column))
        self.column += width
        self.columns = max(self.columns, self.column)

    def result(self):
        rows = self.row + 1
        expected = {(row, column) for row in range(rows) for column in range(self.columns)}
        if self.occupied != expected:
            raise ValueError("Source HTML is not a complete rectangular table")
        return {"rows": rows, "columns": self.columns, "cells": self.cells}


def freeze_annotations(annotation):
    if sha256(annotation) != ANNOTATION_SHA256:
        raise ValueError("Annotation checksum mismatch")
    cases = []
    for line in annotation.decode("utf-8").splitlines():
        record = json.loads(line)
        filename = record["filename"]
        if filename not in IMAGE_SHA256:
            raise ValueError(f"Unexpected upstream image: {filename}")
        content = record["html"]
        tokens = content["structure"]["tokens"]
        cells = content.get("cells", content.get("cell"))
        grid = LogicalGrid()
        grid.feed("".join(tokens))
        expected = grid.result()
        if not isinstance(cells, list) or len(cells) != len(expected["cells"]):
            raise ValueError(f"Source structure/text count differs: {filename}")
        cases.append({"file": filename, "sha256": IMAGE_SHA256[filename],
                      "structure": tokens, "cells": cells, "expected": expected})
    if len(cases) != len(IMAGE_SHA256) or {case["file"] for case in cases} != set(IMAGE_SHA256):
        raise ValueError("The pinned annotation must contain all 20 distinct examples")
    return {"source": BASE_URL, "revision": REVISION, "annotation_sha256": ANNOTATION_SHA256, "cases": cases}


def load_verified_fixture(root):
    manifest = freeze_annotations(verified_bytes(root / "truth.jsonl", ANNOTATION_SHA256))
    saved = json.loads((root / "frozen.json").read_text(encoding="utf-8"))
    if saved != manifest:
        raise ValueError("frozen.json differs from the pinned original annotation; prepare it again")
    for case in manifest["cases"]:
        verified_bytes(root / case["file"], case["sha256"])
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path, help="Local ignored build directory for images and truth")
    arguments = parser.parse_args()
    root = arguments.output.expanduser().resolve()
    root.mkdir(parents=True, exist_ok=True)
    annotation = fetch_verified(BASE_URL + ANNOTATION_FILE, root / "truth.jsonl", ANNOTATION_SHA256)
    manifest = freeze_annotations(annotation)
    for case in manifest["cases"]:
        fetch_verified(BASE_URL + case["file"], root / case["file"], case["sha256"])
    (root / "frozen.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Verified {len(manifest['cases'])} official examples at revision {REVISION}")
    print(f"Local fixture: {root}")


if __name__ == "__main__":
    main()
