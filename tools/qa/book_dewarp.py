"""Local UVDoc geometry and extraction-equivalence probe, without training.

Example: python tools/qa/book_dewarp.py --source-model build/rc12-book/uvdoc.onnx
  --output build/rc12-book/probe image1.jpg image2.png
Requires numpy, opencv-python and onnxruntime. Images remain local; do not commit
third-party photographs without checking their separate reproduction rights.
This host probe evaluates geometry and the preliminary morphology guard only.
The app additionally runs its existing PaddleOCR text detector to protect short
edge headings/folios. Run BookDewarpTest on Android to verify that final guard.
"""
import argparse
import hashlib
import json
from pathlib import Path
import time

import cv2
import numpy as np
import onnxruntime as ort


def align_resize(image, height, width):
    """Float32 bilinear / align_corners, independent of OpenCV's table rounding."""
    h, w = image.shape[:2]
    xx = np.arange(width, dtype=np.float32) * np.float32((w - 1) / (width - 1))
    yy = np.arange(height, dtype=np.float32) * np.float32((h - 1) / (height - 1))
    x0 = np.floor(xx).astype(int)
    y0 = np.floor(yy).astype(int)
    fx, fy = (xx - x0).astype(np.float32), (yy - y0).astype(np.float32)
    a = image[y0[:, None], x0[None, :]]
    b = image[y0[:, None], np.minimum(x0 + 1, w - 1)[None, :]]
    c = image[np.minimum(y0 + 1, h - 1)[:, None], x0[None, :]]
    d = image[np.minimum(y0 + 1, h - 1)[:, None], np.minimum(x0 + 1, w - 1)[None, :]]
    return ((a * (1 - fx)[None, :, None] + b * fx[None, :, None]) * (1 - fy)[:, None, None]
            + (c * (1 - fx)[None, :, None] + d * fx[None, :, None]) * fy[:, None, None])


def maps(grid, shape):
    h, w = shape[:2]
    dense = align_resize(grid[0].transpose(1, 2, 0), h, w)
    return (dense[:, :, 0] + 1) * np.float32(.5 * (w - 1)), (dense[:, :, 1] + 1) * np.float32(.5 * (h - 1))


def exact_grid_sample(image, mx, my):
    h, w = image.shape[:2]
    x0, y0 = np.floor(mx).astype(int), np.floor(my).astype(int)
    fx, fy = (mx - x0).astype(np.float32), (my - y0).astype(np.float32)
    result = np.zeros(image.shape, dtype=np.float32)
    for dx, dy, weight in [(0, 0, (1 - fx) * (1 - fy)), (1, 0, fx * (1 - fy)),
                            (0, 1, (1 - fx) * fy), (1, 1, fx * fy)]:
        x, y = x0 + dx, y0 + dy
        valid = (x >= 0) & (x < w) & (y >= 0) & (y < h)
        result[valid] += image[y[valid], x[valid]] * weight[valid, None]
    return result


def stats(grid, shape):
    h, w = shape[:2]
    g = grid[0].transpose(1, 2, 0)
    gy, gx = g.shape[:2]
    u, v = np.meshgrid(np.linspace(0, 1, gx), np.linspace(0, 1, gy))
    basis = ((g[0, 0] * (1 - u[:, :, None]) + g[0, -1] * u[:, :, None]) * (1 - v[:, :, None])
             + (g[-1, 0] * (1 - u[:, :, None]) + g[-1, -1] * u[:, :, None]) * v[:, :, None])
    delta = (g - basis) * [.5 * (w - 1), .5 * (h - 1)]
    top, bottom = np.diff(g[:-1], axis=1), np.diff(g[1:], axis=1)
    left, right = np.diff(g[:, :-1], axis=0), np.diff(g[:, 1:], axis=0)
    determinants = [a[:, :, 0] * b[:, :, 1] - a[:, :, 1] * b[:, :, 0]
                    for a, b in [(top, left), (top, right), (bottom, left), (bottom, right)]]
    return dict(nonlinear_displacement=float(np.sqrt((delta ** 2).sum(axis=2).mean()) / np.hypot(w, h)),
                outside_fraction=float((np.abs(g).max(axis=2) > 1).mean()),
                folded_fraction=float((np.min(determinants, axis=0) <= 1e-7).mean()),
                signed_area=float((determinants[0] + determinants[3]).sum() * .5),
                grid_min=float(g.min()), grid_max=float(g.max()))


def content_guard(image, grid):
    """Mirror the preliminary morphology guard, not the final OCR-region guard."""
    h, w = image.shape[:2]
    scale = min(1, 1200 / max(h, w))
    image = cv2.resize(image, (round(w * scale), round(h * scale)), interpolation=cv2.INTER_AREA)
    h, w = image.shape[:2]
    g = grid[0].transpose(1, 2, 0)
    outline = np.concatenate([g[0], g[1:, -1], g[-1, -2::-1], g[-2:0:-1, 0]])
    mask = np.zeros((h, w), np.uint8)
    cv2.fillPoly(mask, [np.rint((outline + 1) * [.5 * (w - 1), .5 * (h - 1)]).astype(np.int32)], 255)
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    local = cv2.GaussianBlur(gray, (0, 0), 5)
    ink = (local.astype(np.int16) - gray.astype(np.int16) > 14).astype(np.uint8)
    horizontal = cv2.morphologyEx(ink, cv2.MORPH_OPEN,
        cv2.getStructuringElement(cv2.MORPH_RECT, (max(12, round(w * .30)), 1)))
    vertical = cv2.morphologyEx(ink, cv2.MORPH_OPEN,
        cv2.getStructuringElement(cv2.MORPH_RECT, (1, max(12, round(h * .25)))))
    rules = horizontal | vertical
    lost_rules = int(((rules > 0) & (mask == 0)).sum())
    ruled_table = horizontal.sum() >= 3 * w and vertical.sum() >= 2 * h
    record = dict(accepted=True, protected_table=bool(ruled_table), lost_rule_pixels=lost_rules,
                  total_rule_pixels=int(rules.sum()))
    if ruled_table and lost_rules >= max(12, rules.sum() * .002):
        return dict(record, accepted=False, reason="table-rule-loss")
    closed = cv2.morphologyEx(ink, cv2.MORPH_CLOSE,
        cv2.getStructuringElement(cv2.MORPH_RECT, (max(3, w // 40), 1)))
    count, labels, regions, _ = cv2.connectedComponentsWithStats(closed, 8)
    for label in range(1, count):
        x, y, cw, ch, _ = regions[label]
        if (y < h * .03 or y + ch > h * .97 or ch < max(3, h * .007)
                or ch > h * .09 or cw < w * .13 or cw < ch * 2.5):
            continue
        group = (labels[y:y + ch, x:x + cw] == label) & (ink[y:y + ch, x:x + cw] > 0)
        total = int(group.sum())
        lost = int((group & (mask[y:y + ch, x:x + cw] == 0)).sum())
        if .05 <= total / (cw * ch) <= .7 and lost >= max(6, total * .01):
            return dict(record, accepted=False, reason="text-group-loss", lost_text_pixels=lost)
    return record


def textline_bow(image):
    """Heuristic geometry diagnostic, not OCR accuracy or a benchmark metric.

    At height 1200, join neighboring glyph strokes horizontally, retain long
    isolated line groups, estimate 22 lower ink quantiles, and fit a quadratic.
    Report fitted center-to-endpoint chord bow. Descenders and line grouping can
    bias this; no inference about recognition accuracy is made from this value.
    """
    h, w = image.shape[:2]
    image = cv2.resize(image, (round(w * 1200 / h), 1200))
    h, w = image.shape[:2]
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    local = cv2.GaussianBlur(gray, (0, 0), 5)
    ink = (local.astype(np.int16) - gray.astype(np.int16) > 14).astype(np.uint8)
    closed = cv2.morphologyEx(ink, cv2.MORPH_CLOSE,
        cv2.getStructuringElement(cv2.MORPH_RECT, (29, 1)))
    count, labels, regions, _ = cv2.connectedComponentsWithStats(closed, 8)
    bows = []
    for label in range(1, count):
        x, y, cw, ch, _ = regions[label]
        if x < .04 * w or x + cw > .96 * w or cw < .40 * w or ch < 8 or ch > 60 or y < 60 or y + ch > 1140:
            continue
        yy, xx = np.where((labels[y:y + ch, x:x + cw] == label) & (ink[y:y + ch, x:x + cw] > 0))
        xx, yy = xx + x, yy + y
        bins = []
        for begin in np.linspace(x, x + cw, 23)[:-1]:
            selected = (xx >= begin) & (xx < begin + cw / 22)
            if selected.sum() >= 6:
                bins.append([begin + cw / 44, np.quantile(yy[selected], .87)])
        if len(bins) < 14:
            continue
        xs, ys = np.array(bins).T
        coefficients = np.polyfit((xs - xs.mean()) / cw, ys, 2)
        bows.append(float(abs(coefficients[0]) * .25))
    return dict(line_groups=len(bows), median_px=float(np.median(bows)) if bows else None,
                max_px=float(max(bows)) if bows else None)


def session(path):
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    return ort.InferenceSession(str(path), options, providers=["CPUExecutionProvider"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("images", nargs="+", type=Path)
    parser.add_argument("--model", type=Path, default=Path("app/src/main/assets/vision/uvdoc.onnx"))
    parser.add_argument("--source-model", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    predictor = session(args.model)
    original = session(args.source_model) if args.source_model else None
    records = []
    for file in args.images:
        image = cv2.imread(str(file))
        if image is None:
            raise ValueError(f"Cannot read {file}")
        h, w = image.shape[:2]
        if max(h, w) > 3000:
            image = cv2.resize(image, (round(w * 3000 / max(h, w)), round(h * 3000 / max(h, w))))
        prep = align_resize(image.astype(np.float32) / 255, 712, 488).transpose(2, 0, 1)[None].copy()
        begin = time.monotonic()
        grid = predictor.run(None, {"image": prep})[0]
        record = dict(name=file.name, sha256=hashlib.sha256(file.read_bytes()).hexdigest(),
                      image_shape=list(image.shape), inference_ms=round(1000 * (time.monotonic() - begin), 2),
                      **stats(grid, image.shape), preliminary_content_guard=content_guard(image, grid),
                      detected_text_guard="not-evaluated-by-host-probe")
        mx, my = maps(grid, image.shape)
        output = cv2.remap(image, mx, my, cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT, borderValue=(255, 255, 255))
        kind = "candidate" if record["preliminary_content_guard"]["accepted"] else "rejected-candidate"
        cv2.imwrite(str(args.output / (file.stem + "-" + kind + ".png")), output)
        cv2.imwrite(str(args.output / (file.stem + "-input.png")), image)
        record["textline_bow_diagnostic"] = dict(before=textline_bow(image), after=textline_bow(output))
        if original:
            # Compare on an independently resized <=900px image, so full-model
            # high-resolution GridSample tensors do not dominate local RAM.
            h, w = image.shape[:2]
            scale = min(1, 900 / max(h, w))
            test = cv2.resize(image, (round(w * scale), round(h * scale))).astype(np.float32) / 255
            predicted = predictor.run(None, {"image": align_resize(test, 712, 488).transpose(2, 0, 1)[None].copy()})[0]
            reference = original.run(None, {"image": test.transpose(2, 0, 1)[None].copy()})[0][0].transpose(1, 2, 0)
            tx, ty = maps(predicted, test.shape)
            independent = exact_grid_sample(test, tx, ty)
            error = np.abs(independent - reference)
            cv_output = cv2.remap(test, tx, ty, cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT)
            record["original_equivalence"] = dict(max_abs_255=float(error.max() * 255),
                mean_abs_255=float(error.mean() * 255), cv_max_abs_255=float(np.abs(cv_output - reference).max() * 255),
                cv_mean_abs_255=float(np.abs(cv_output - reference).mean() * 255))
        records.append(record)
        print(json.dumps(record, ensure_ascii=False))
    (args.output / "metrics.json").write_text(json.dumps(records, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()
