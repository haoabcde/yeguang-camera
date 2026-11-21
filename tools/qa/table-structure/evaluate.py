#!/usr/bin/env python3
"""Score Android table output against verified, locally prepared PubTabNet examples.

Structure, text and same-cell native/scaled comparisons are separate metrics.
Python 3.10+; standard library only. No fixtures are distributed in this folder.
"""
import argparse
import html
import json
from pathlib import Path
import re

from prepare import load_verified_fixture


def compact(value):
    """Remove source HTML styling and whitespace only; preserve digits, signs, case and punctuation."""
    return re.sub(r"\s+", "", html.unescape(re.sub(r"<[^>]+>", "", value)))


def edit_distance(before, after):
    row = list(range(len(after) + 1))
    for index, left in enumerate(before, 1):
        previous = row
        row = [index]
        for column, right in enumerate(after, 1):
            row.append(min(previous[column] + 1, row[-1] + 1,
                           previous[column - 1] + (left != right)))
    return row[-1]


def matching_table(record, field, expected):
    for table in record[field]:
        if table["rows"] == expected["rows"] and table["columns"] == expected["columns"] \
                and [cell[:4] for cell in table["cells"]] == expected["cells"]:
            return table
    return None


def text_counts():
    return {"cells": 0, "cellTextExact": 0, "characters": 0, "characterEdits": 0,
            "numericCells": 0, "numericExact": 0}


def score_cell(counts, expected, actual):
    counts["cells"] += 1
    counts["cellTextExact"] += expected == actual
    counts["characters"] += len(expected)
    counts["characterEdits"] += edit_distance(expected, actual)
    if re.fullmatch(r"[+−-]?[0-9][0-9.,]*", expected):
        counts["numericCells"] += 1
        counts["numericExact"] += expected == actual


def evaluate(manifest, records):
    truth = {case["file"]: case for case in manifest["cases"]}
    by_name = {}
    for record in records:
        name = record["file"]
        if name not in truth or name in by_name:
            raise ValueError(f"Unexpected or duplicate result: {name}")
        if record["sha256"] != truth[name]["sha256"]:
            raise ValueError(f"Android input checksum differs: {name}")
        by_name[name] = record
    if set(by_name) != set(truth):
        raise ValueError("Results must include all 20 examples; partial runs are not a full regression")
    fields = [field for field in ("nativeTables", "tables") if all(field in item for item in records)]
    if "tables" not in fields:
        raise ValueError("Missing Android App tables output")
    paths = {field: {"cases": len(records), "structureExactCases": 0, "textOnExactStructures": text_counts(),
                     "incorrectStructureFiles": []} for field in fields}
    paired = {"available": "nativeTables" in fields, "cases": 0, "files": [],
              "native": text_counts(), "scaled": text_counts()}
    model_exact = 0
    cases = []
    for name, source in truth.items():
        record = by_name[name]
        expected = source["expected"]
        direct = record.get("rows") == expected["rows"] and record.get("columns") == expected["columns"] \
            and [cell[:4] for cell in record["structure"]] == expected["cells"]
        model_exact += direct
        matches = {}
        status = {"file": name, "modelStructureExact": direct}
        for field in fields:
            table = matching_table(record, field, expected)
            matches[field] = table
            status[field + "StructureExact"] = table is not None
            if table is None:
                paths[field]["incorrectStructureFiles"].append(name)
                continue
            paths[field]["structureExactCases"] += 1
            for index, value in enumerate(source["cells"]):
                score_cell(paths[field]["textOnExactStructures"], compact("".join(value["tokens"])),
                           compact(table["cells"][index][4]))
        if matches.get("nativeTables") is not None and matches.get("tables") is not None:
            paired["cases"] += 1
            paired["files"].append(name)
            for index, value in enumerate(source["cells"]):
                expected_text = compact("".join(value["tokens"]))
                score_cell(paired["native"], expected_text, compact(matches["nativeTables"]["cells"][index][4]))
                score_cell(paired["scaled"], expected_text, compact(matches["tables"]["cells"][index][4]))
        cases.append(status)
    return {
        "note": "Public examples only, not an unseen commercial-accuracy benchmark. Some upstream examples are marked train.",
        "definitions": {
            "structureExact": "Same row/column counts and complete ordered [row,column,rowspan,colspan] list. Text is scored separately.",
            "textOnExactStructures": "Only cases with completely correct structure in that path; includes empty cells. Whitespace is ignored.",
            "paired": "The identical cells from cases whose complete structure is correct in BOTH native and scaled paths.",
            "numeric": "Original digits with optional +, -, U+2212 sign and comma/dot separators; no semantic number correction.",
            "characterEdits": "Unicode edit distance after removing styling and whitespace; no case folding or digit/sign normalization."
        },
        "source": {key: manifest[key] for key in ("source", "revision", "annotation_sha256")},
        "model": {"cases": len(records), "structureExactCases": model_exact},
        "paths": paths,
        "paired": paired,
        "cases": cases,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixtures", required=True, type=Path, help="Directory produced by prepare.py")
    parser.add_argument("--results", type=Path, help="Android output; default: FIXTURES/android-results.json")
    parser.add_argument("--output", type=Path, help="Report; default: FIXTURES/evaluation.json")
    arguments = parser.parse_args()
    root = arguments.fixtures.expanduser().resolve()
    manifest = load_verified_fixture(root)
    results = arguments.results or root / "android-results.json"
    report = evaluate(manifest, json.loads(results.read_text(encoding="utf-8")))
    destination = arguments.output or root / "evaluation.json"
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"model": report["model"], "paths": report["paths"], "paired": report["paired"]},
                     ensure_ascii=False, indent=2))
    print(f"Local report: {destination}")


if __name__ == "__main__":
    main()
