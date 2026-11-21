"""Score DOCX exports of FieldRunner; crop and runtime data come from its run.json."""
import argparse
import hashlib
import json
import re
import unicodedata
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

SAMPLES = json.loads(Path(__file__).with_name("samples.json").read_text())
NAMESPACE = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"


def normalize(value):
    text = unicodedata.normalize("NFKC", value)
    return "".join(c for c in text if c.isalnum() or c in "$%")


def score(export_dir, version):
    run = json.loads((export_dir / "run.json").read_text())
    results = {}
    totals = {"auto": 0, "manual": 0, "anchors": 0}
    for name, sample in SAMPLES.items():
        item = dict(run[name])
        anchors = sample["anchors"]
        item["subject"] = sample["subject"]
        item["anchors"] = len(anchors)
        totals["anchors"] += len(anchors)
        for mode in ("auto", "manual"):
            path = export_dir / f"{name}-{mode}.docx"
            with zipfile.ZipFile(path) as archive:
                xml = ET.fromstring(archive.read("word/document.xml"))
            text = normalize("".join(node.text or "" for node in xml.iter(NAMESPACE + "t")))
            missing = [anchor for anchor in anchors if normalize(anchor) not in text]
            matched = len(anchors) - len(missing)
            item[mode] = {
                "matched": matched,
                "missing_exactly": missing,
                "docx_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            }
            totals[mode] += matched
        pdf = (export_dir / f"{name}-manual.pdf").read_bytes()
        if not pdf.startswith(b"%PDF-"):
            raise ValueError(f"{name} PDF export is invalid")
        item["pdf_sha256"] = hashlib.sha256(pdf).hexdigest()
        results[name] = item
    return {
        "version": version,
        "platform": "Android 16/API 36 ARM64 emulator",
        "metric": "每项为预先转录的连续字符串。NFKC、忽略空白和标点（保留 $ 和 %）后，在未校对的 DOCX 正文任意位置精确匹配，不计字符错误率或整页准确率。",
        "procedure": "同一整张真实照片，经 App 的默认 1000 像素探测、AUTO 增强、正文识别、Word 导出。manual 改为事先标定的目标纸面四角。多张纸同框时自动裁边可能选中另一张；可在全文中命中重复文字。",
        "sampling": "便利抽样。两位作者的三张公开照片，每个场景一张，无法代表真实人群、设备分布、处理速度或夸克的表现。",
        "source_catalog": "../../tools/qa/field-ocr/samples.json",
        "timing": "JSON 的 auto_ms / manual_ms 从 OCR 开始到 Word 写完，包含模型打开，不含导入、裁边探测、PDF 导出与系统保存。",
        "totals": totals,
        "samples": results,
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("export_dir", type=Path, help="pulled run.json and exported DOCX/PDF directory")
    parser.add_argument("--version", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--apk-sha256", help="SHA-256 of the installed app APK")
    args = parser.parse_args()
    data = score(args.export_dir, args.version)
    if args.apk_sha256:
        if not re.fullmatch("[0-9a-f]{64}", args.apk_sha256):
            parser.error("invalid APK SHA-256")
        data["app_apk_sha256"] = args.apk_sha256
    args.output.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(data["totals"], ensure_ascii=False))
