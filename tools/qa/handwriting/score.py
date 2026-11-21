"""Measure unchanged Unicode character error rate and check which reference lines survive in the exported Word."""
import argparse
import hashlib
import json
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

NAMESPACE = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"


def normalize(text):
    return "".join(char for char in text if not char.isspace())


def compare(lines, sample):
    ref = normalize("".join(lines))
    # Among paths with the same edit count, keep the most exact characters.
    # A fully deleted row is distinguished from a row of substituted characters.
    scores = [[(i, 0)] + [(0, 0)] * len(sample) for i in range(len(ref) + 1)]
    scores[0] = [(j, 0) for j in range(len(sample) + 1)]
    steps = [["D"] + ["I"] * len(sample) for _ in range(len(ref) + 1)]
    for i, wanted in enumerate(ref, 1):
        for j, actual in enumerate(sample, 1):
            edits, neg_matches = scores[i - 1][j - 1]
            same = actual == wanted
            sub = (edits + (not same), neg_matches - same)
            edits, neg_matches = scores[i - 1][j]
            delete = (edits + 1, neg_matches)
            edits, neg_matches = scores[i][j - 1]
            insert = (edits + 1, neg_matches)
            best = min(sub, delete, insert)
            scores[i][j] = best
            steps[i][j] = ("=" if same else "S") if best == sub else ("D" if best == delete else "I")
    i, j = len(ref), len(sample)
    operations = [""] * i
    while i or j:
        step = steps[i][j]
        if step != "I":
            i -= 1
            operations[i] = step
        if step != "D":
            j -= 1
    first = 0
    alignment = []
    for line in lines:
        end = first + len(normalize(line))
        span = operations[first:end]
        alignment.append({"matched": span.count("="), "substituted": span.count("S"), "deleted": span.count("D")})
        first = end
    return scores[-1][-1][0], alignment


def text_from_docx(path):
    with zipfile.ZipFile(path) as archive:
        root = ET.fromstring(archive.read("word/document.xml"))
    lines = []
    for p in root.iter(NAMESPACE + "p"):
        text = "".join((node.text or "") if node.tag == NAMESPACE + "t" else "\n"
                       for node in p.iter() if node.tag in (NAMESPACE + "t", NAMESPACE + "br"))
        lines.extend(text.splitlines())
    return lines


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path, help="Directory with prepared inputs.json and pulled outputs")
    parser.add_argument("report", type=Path)
    parser.add_argument("--samples", type=Path, default=Path(__file__).with_name("samples.json"))
    parser.add_argument("--apk", type=Path, required=True)
    args = parser.parse_args()
    cases = json.loads(args.samples.read_text())
    manifest = json.loads((args.directory / "inputs.json").read_text())
    run = json.loads((args.directory / "run.json").read_text())
    if set(cases) != set(manifest) or set(cases) != set(run):
        raise ValueError("Ground truth, input manifest and device results must name the same cases.")
    totals = {mode: {"characters": 0, "errors": 0, "reference_lines": 0, "exact_lines": 0, "fully_deleted_lines": 0}
              for mode in ["print", "hand", "print-original", "hand-original", "hand-auto"]}
    rows = {}
    for key, case in cases.items():
        ref = normalize("".join(case["lines"]))
        if manifest[key] != run[key]["input_sha256"]:
            raise ValueError(f"Device input digest mismatch: {key}")
        selected = run[key].get("hand_result_from")
        if selected not in ("hand-auto", "hand-original") or run[key].get("print_filter") != "AUTO":
            raise ValueError(f"Missing default-entry-point filter record: {key}")
        if run[key]["hand_filter"] != run[key].get(f"{selected}_filter"):
            raise ValueError(f"Inconsistent selected handwriting filter: {key}")
        row = {"reference_lines": case["lines"], "source": case["page"], **run[key]}
        for mode, total in totals.items():
            path = args.directory / f"{key}-{mode}.docx"
            candidate = text_from_docx(path)
            text = normalize("".join(candidate))
            errors, alignment = compare(case["lines"], text)
            missing = [i + 1 for i, line in enumerate(alignment) if line["deleted"] > 0
                       and not line["matched"] and not line["substituted"]]
            exact_lines = sum(normalize(line) in text for line in case["lines"])
            row[mode] = {"exported_lines": candidate, "characters": len(ref), "errors": errors,
                         "cer": round(errors / len(ref), 4), "exact_lines": exact_lines,
                         "fully_deleted_line_numbers": missing, "source_line_alignment": alignment,
                         "docx_sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
            total["characters"] += len(ref)
            total["errors"] += errors
            total["exact_lines"] += exact_lines
            total["reference_lines"] += len(case["lines"])
            total["fully_deleted_lines"] += len(missing)
        if row["hand"]["docx_sha256"] != row[selected]["docx_sha256"]:
            raise ValueError(f"The default handwriting file differs from the selected filter export: {key}")
        rows[key] = row
    for total in totals.values():
        total["cer"] = round(total["errors"] / total["characters"], 4)
    result = {"apk_sha256": hashlib.sha256(args.apk.read_bytes()).hexdigest(),
              "metric": "从导出的 DOCX 读取正文，按横行顺序串联。仅忽略空白；数字、标点、货币符号、简繁写法均计差异。CER 为替换、插入、删除的最少总次数除以原文字数。整行精确命中表示完整原行可在正文中找到，错一个字也记作未完整命中。逐页保留原行和输出供复核漏行。",
              "samples": rows, "totals": totals}
    args.report.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(totals, ensure_ascii=False))


if __name__ == "__main__":
    main()
