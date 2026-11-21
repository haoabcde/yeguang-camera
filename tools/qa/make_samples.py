"""Generate non-sensitive input images for Android scanner acceptance testing."""

import argparse
from pathlib import Path

from PIL import Image, ImageDraw, ImageEnhance, ImageFont


def procurement(font_path: str) -> Image.Image:
    paper = Image.new("RGB", (1200, 1630), "#f4f1eb")
    art = ImageDraw.Draw(paper)

    def write(text, x, y, size, anchor=None):
        art.text((x, y), text, fill="#242321", font=ImageFont.truetype(font_path, size), anchor=anchor)

    write("采 购 清 单", 600, 174, 72, "mm")
    write("项目名称：办公用品采购", 82, 255, 35)
    write("编号：CG-2026-0118", 82, 310, 35)
    write("采购日期：2026 年 11 月 20 日", 1118, 314, 33, "rt")
    columns = [82, 178, 411, 651, 756, 873, 1120]
    rows = [408 + index * 64 for index in range(14)]
    for y in rows:
        art.line((columns[0], y, columns[-1], y), fill="#46413b", width=3)
    for x in columns:
        art.line((x, rows[0], x, rows[-1]), fill="#46413b", width=3)
    items = [
        ["序号", "物品名称", "规格型号", "单位", "数量", "金额（元）"],
        ["001", "A4 复印纸", "70g 500张", "包", "50", "1,200.00"],
        ["002", "中性笔", "黑色 0.5 mm", "盒", "10", "180.00"],
        ["003", "中性笔", "蓝色 0.5 mm", "盒", "5", "90.00"],
        ["004", "笔记本", "A5 80 页", "本", "30", "195.00"],
        ["005", "文件夹", "A4 双夹", "个", "40", "160.00"],
        ["006", "档案盒", "A4 75 mm", "个", "20", "160.00"],
        ["007", "订书机", "12 号", "个", "10", "160.00"],
        ["008", "订书针", "1000 枚", "盒", "20", "50.00"],
        ["009", "固体胶", "21 g", "支", "30", "105.00"],
        ["010", "便签", "76 mm", "包", "30", "150.00"],
        ["011", "文件袋", "A4 透明", "个", "100", "120.00"],
        ["012", "回形针", "100 枚", "盒", "", "-20.00"],
    ]
    for row, values in enumerate(items):
        for column, text in enumerate(values):
            write(text, (columns[column] + columns[column + 1]) // 2, rows[row] + 32, 29, "mm")
    write("备注", 82, 1306, 40)
    write("1. 以上物资用于日常办公。", 82, 1374, 30)
    write("2. 到货后按采购单核对数量。", 82, 1426, 30)
    art.ellipse((880, 1292, 1080, 1492), outline="#b75a53", width=5)
    art.text((980, 1390), "采购专用章", fill="#b75a53",
             font=ImageFont.truetype(font_path, 28), anchor="mm")
    return paper


def note(font_path: str) -> Image.Image:
    page = Image.new("RGB", (1120, 1450), "white")
    draw = ImageDraw.Draw(page)
    for value, y, font_size in [
        ("项目验收记录", 150, 61),
        ("Inspection Report 2026", 285, 45),
        ("数量与金额已核对。", 440, 38),
        ("合同与验收单已归档。", 510, 38),
        ("Stock check complete.", 670, 38),
    ]:
        draw.text((115, y), value, fill="#222224", font=ImageFont.truetype(font_path, font_size))
    return page


def photograph(paper: Image.Image, angle: float, shade: float) -> Image.Image:
    page = ImageEnhance.Brightness(paper).enhance(shade)
    flattened = page.convert("RGBA").rotate(angle, resample=Image.Resampling.BICUBIC, expand=True)
    width, height = flattened.size
    surround = Image.new("RGB", (width + 86, height + 140), "#887b6b")
    desk = ImageDraw.Draw(surround)
    for y in range(3, height + 140, 8):
        tone = 120 + (y % 29)
        desk.line((0, y, width + 86, y), fill=(tone, tone - 10, tone - 22), width=1)
    surround.paste(flattened, (43, 70), flattened)
    return surround


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("out", type=Path)
    parser.add_argument("--font", default="/System/Library/Fonts/STHeiti Medium.ttc")
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    table = procurement(args.font)
    text = note(args.font)
    photograph(table, -2.5, .91).save(args.out / "01-scan-table.jpg", quality=94)
    text.save(args.out / "02-scan-word.png")
    rotate = text.transpose(Image.Transpose.ROTATE_90)
    exif = Image.Exif()
    exif[274] = 6
    rotate.save(args.out / "03-scan-exif.jpg", quality=92, exif=exif)

    for index in range(20):
        page = (table if index % 3 == 0 else text).copy()
        draw = ImageDraw.Draw(page)
        draw.rectangle((90, 60, 380, 110), fill="#f4f1eb")
        draw.text((98, 66), f"Batch QA {index + 1:02}", fill="#292525",
                  font=ImageFont.truetype(args.font, 30))
        if index % 4 == 0:
            page = page.transpose(Image.Transpose.ROTATE_270)
        photo = photograph(page, (index % 7 - 3) * 1.2, .70 + (index % 5) * .07)
        photo.save(args.out / f"batch-{index + 1:02}.jpg", quality=87)


if __name__ == "__main__":
    main()
