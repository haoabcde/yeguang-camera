package com.scandesk.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;

import com.google.mlkit.vision.text.Text;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class TableVision {
    private TableVision() {}

    static Bitmap straighten(Bitmap bitmap) throws IOException {
        ScanImages.requireVision();
        Mat input = new Mat();
        Mat gray = new Mat();
        Mat edges = new Mat();
        Mat segments = new Mat();
        double angle;
        try {
            Utils.bitmapToMat(bitmap, input);
            Imgproc.cvtColor(input, gray, Imgproc.COLOR_RGBA2GRAY);
            if (gray.cols() > 1100) {
                Imgproc.resize(gray, gray, new Size(1100, (double) gray.rows() * 1100 / gray.cols()),
                        0, 0, Imgproc.INTER_AREA);
            }
            Imgproc.Canny(gray, edges, 36, 120);
            double horizontalMin = Math.max(45, gray.cols() * .21);
            Imgproc.HoughLinesP(edges, segments, 1, Math.PI / 1800,
                    Math.max(28, gray.cols() / 15), horizontalMin,
                    gray.cols() / 26.0);
            List<Segment> rows = new ArrayList<>();
            for (int i = 0; i < segments.rows(); i++) {
                double[] points = segments.get(i, 0);
                double dx = points[2] - points[0];
                double dy = points[3] - points[1];
                if (Math.abs(dx) >= horizontalMin && Math.abs(dy / dx) <= .20) {
                    rows.add(new Segment(Math.toDegrees(Math.atan(dy / dx)), Math.abs(dx)));
                }
            }
            angle = medianAngle(rows);
        } finally {
            ScanImages.release(input, gray, edges, segments);
        }
        if (Math.abs(angle) < .24) return bitmap;
        Matrix transform = new Matrix();
        transform.setRotate((float) -angle);
        RectF bounds = new RectF(0, 0, bitmap.getWidth(), bitmap.getHeight());
        transform.mapRect(bounds);
        transform.postTranslate(-bounds.left, -bounds.top);
        float scale = Math.min(1, ScanImages.OCR_EDGE / Math.max(bounds.width(), bounds.height()));
        transform.postScale(scale, scale);
        Bitmap result = Bitmap.createBitmap(Math.max(1, (int) Math.ceil(bounds.width() * scale)),
                Math.max(1, (int) Math.ceil(bounds.height() * scale)), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        try {
            canvas.drawColor(Color.WHITE);
            canvas.drawBitmap(bitmap, transform, new Paint(Paint.FILTER_BITMAP_FLAG));
        } finally {
            canvas.setBitmap(null);
        }
        return result;
    }

    private static double medianAngle(List<Segment> lines) {
        if (lines.size() < 3) return 0;
        lines.sort(Comparator.comparingDouble(line -> line.angle));
        double total = 0;
        for (Segment line : lines) total += line.length;
        double accumulated = 0;
        for (Segment line : lines) {
            accumulated += line.length;
            if (accumulated >= total / 2) return line.angle;
        }
        return 0;
    }

    static List<OfficeTable> recognize(Bitmap bitmap, Text text, ChineseOcr.Page ocr, int page)
            throws IOException {
        return recognize(bitmap, tokens(text), ocr, page, null, false);
    }

    static List<List<android.graphics.Rect>> ruledRows(Bitmap bitmap) throws IOException {
        List<List<android.graphics.Rect>> regions = new ArrayList<>();
        recognize(bitmap, Collections.emptyList(), null, 0, regions, false);
        return regions;
    }

    static List<OfficeTable> handwritten(Bitmap bitmap, List<HandwritingOcr.Line> lines, int page)
            throws IOException {
        List<Token> tokens = new ArrayList<>();
        for (HandwritingOcr.Line line : lines) {
            android.graphics.Rect box = new android.graphics.Rect(
                    Math.round(line.bounds.left * bitmap.getWidth()), Math.round(line.bounds.top * bitmap.getHeight()),
                    Math.round(line.bounds.right * bitmap.getWidth()), Math.round(line.bounds.bottom * bitmap.getHeight()));
            tokens.add(new Token(box, line.text, line));
        }
        return recognize(bitmap, tokens, null, page, null, true);
    }

    private static List<OfficeTable> recognize(Bitmap bitmap, List<Token> tokens, ChineseOcr.Page ocr, int page,
            List<List<android.graphics.Rect>> regions, boolean handwriting) throws IOException {
        if (regions == null && tokens.isEmpty()) return Collections.emptyList();
        ScanImages.requireVision();
        Mat rgba = new Mat();
        Mat gray = new Mat();
        Mat ink = new Mat();
        Mat horizontal = new Mat();
        Mat vertical = new Mat();
        Mat intersections = new Mat();
        Mat hierarchy = new Mat();
        List<MatOfPoint> contours = new ArrayList<>();
        Mat rowElement = Imgproc.getStructuringElement(Imgproc.MORPH_RECT,
                new Size(Math.max(22, Math.min(120, bitmap.getWidth() / 15)), 1));
        Mat columnElement = Imgproc.getStructuringElement(Imgproc.MORPH_RECT,
                new Size(1, Math.max(19, Math.min(85, bitmap.getHeight() / 35))));
        Mat strengthen = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3));
        Mat connect = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(5, 5));
        List<OfficeTable> tables = new ArrayList<>();
        List<android.graphics.Rect> ruledBounds = new ArrayList<>();
        try {
            Utils.bitmapToMat(bitmap, rgba);
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);
            Imgproc.adaptiveThreshold(gray, ink, 255, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                    Imgproc.THRESH_BINARY_INV, 31, 9);
            Imgproc.dilate(ink, ink, strengthen);
            Imgproc.morphologyEx(ink, horizontal, Imgproc.MORPH_OPEN, rowElement);
            Imgproc.morphologyEx(ink, vertical, Imgproc.MORPH_OPEN, columnElement);
            Core.bitwise_or(horizontal, vertical, intersections);
            Imgproc.dilate(intersections, intersections, connect);
            Imgproc.findContours(intersections, contours, hierarchy, Imgproc.RETR_EXTERNAL,
                    Imgproc.CHAIN_APPROX_SIMPLE);
            List<Rect> candidates = new ArrayList<>();
            for (MatOfPoint contour : contours) {
                Rect bounds = Imgproc.boundingRect(contour);
                if (bounds.width >= Math.max(80, bitmap.getWidth() / 7) && bounds.height >= 45
                        && Imgproc.contourArea(contour) >= bounds.area() * .16) {
                    candidates.add(bounds);
                }
            }
            candidates.sort(Comparator.comparingInt((Rect rect) -> rect.y).thenComparingInt(rect -> rect.x));
            for (Rect bounds : candidates) {
                OfficeTable table = grid(ink, horizontal, vertical, bounds, tokens, ocr, page, tables.size() + 1, regions);
                if (table != null) {
                    tables.add(table);
                    ruledBounds.add(new android.graphics.Rect(bounds.x, bounds.y,
                            bounds.x + bounds.width, bounds.y + bounds.height));
                }
            }
        } finally {
            for (MatOfPoint contour : contours) contour.release();
            ScanImages.release(rgba, gray, ink, horizontal, vertical, intersections, hierarchy,
                    rowElement, columnElement, strengthen, connect);
        }
        if (handwriting) {
            List<HandwritingOcr.Line> remaining = new ArrayList<>();
            for (Token token : tokens) {
                boolean included = false;
                for (OfficeTable table : tables) {
                    for (OfficeTable.Cell cell : table.cells) {
                        if (cell.sourceBounds != null && cell.sourceBounds.contains(
                                token.line.bounds.centerX(), token.line.bounds.centerY())) {
                            included = true;
                            break;
                        }
                    }
                    if (included) break;
                }
                if (!included) remaining.add(token.line);
            }
            if (!remaining.isEmpty()) tables.add(HandwrittenTableLayout.create(remaining, page,
                    tables.isEmpty() ? "手写表格" : "补充文字"));
        } else if (regions == null) {
            Set<Token> used = new HashSet<>();
            List<Token> remaining = new ArrayList<>();
            for (Token token : tokens) {
                boolean ruled = false;
                for (android.graphics.Rect bound : ruledBounds) {
                    if (bound.contains(token.x(), token.y())) { ruled = true; break; }
                }
                if (ruled) used.add(token);
                else remaining.add(token);
            }
            List<Token> separated = new ArrayList<>();
            for (Token token : remaining) {
                List<ElementPart> parts = splitElement(token.text, token.symbols, token.symbolBounds);
                if (parts.size() < 2) separated.add(token);
                else for (ElementPart part : parts) separated.add(new Token(part.bounds, part.text));
            }
            remaining = separated;
            int before = tables.size();
            for (List<Token> candidate : structureCandidates(remaining)) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("已取消");
                if (ocr == null) break;
                android.graphics.Rect bounds = tokenBounds(candidate);
                int padding = Math.max(5, medianHeight(candidate) / 2);
                bounds.inset(-padding, -padding);
                bounds.intersect(0, 0, bitmap.getWidth(), bitmap.getHeight());
                if (bounds.width() >= bitmap.getWidth() * .75f && bounds.height() >= bitmap.getHeight() * .70f) {
                    // A tightly framed table already has regional evidence. Keep blank edge cells and rules.
                    bounds.set(0, 0, bitmap.getWidth(), bitmap.getHeight());
                }
                TableStructureOcr.Result structure = ocr.structure(bounds);
                OfficeTable restored = structured(bitmap, candidate, structure, page, tables.size() + 1);
                if (completeGeometricRows(candidate)) {
                    OfficeTable fallback = infer(candidate, ocr, page, bitmap);
                    if (fallback != null && (restored == null || collapsesRepeatedColumns(candidate, structure, fallback))) {
                        restored = new OfficeTable("第 " + page + " 页表 " + (tables.size() + 1),
                                page, fallback.rows, fallback.columns, true, fallback.cells);
                    }
                }
                if (restored != null) {
                    tables.add(restored);
                    used.addAll(candidate);
                }
            }
            if (tables.size() > before) {
                // A layout model must never silently consume neighbouring notes or another table.
                List<Token> extra = new ArrayList<>();
                for (Token token : remaining) if (!used.contains(token)) extra.add(token);
                if (!extra.isEmpty()) tables.add(supplement(bitmap, extra, page));
            } else if (tables.isEmpty()) {
                Set<Token> retained = new HashSet<>();
                OfficeTable inferred = infer(remaining, ocr, page, bitmap, retained);
                if (inferred != null) {
                    tables.add(inferred);
                    List<Token> extra = new ArrayList<>();
                    for (Token token : remaining) if (!retained.contains(token)) extra.add(token);
                    if (!extra.isEmpty()) tables.add(supplement(bitmap, extra, page));
                }
            }
        }
        return tables;
    }

    /** Repeated whitespace-separated columns nominate regions; prose alone never invokes SLANet. */
    private static List<List<Token>> structureCandidates(List<Token> tokens) {
        List<List<Token>> output = new ArrayList<>();
        if (tokens.size() < 4) return output;
        int height = medianHeight(tokens);
        List<List<Token>> lines = lineGroups(tokens, Math.max(4, Math.round(height * .62f)));
        List<Integer> gaps = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) gaps.add(lines.get(i).get(0).y() - lines.get(i - 1).get(0).y());
        Collections.sort(gaps);
        int spacing = gaps.isEmpty() ? height : gaps.get(gaps.size() / 2);
        int bandGap = Math.max(height * 4, Math.round(spacing * 2.3f));
        List<Token> band = new ArrayList<>();
        int previous = Integer.MIN_VALUE;
        for (List<Token> line : lines) {
            int y = line.get(0).y();
            if (!band.isEmpty() && y - previous > bandGap) {
                splitCandidate(band, output, 0);
                band = new ArrayList<>();
            }
            band.addAll(line);
            previous = y;
        }
        splitCandidate(band, output, 0);
        return output;
    }

    private static void splitCandidate(List<Token> tokens, List<List<Token>> output, int depth) {
        if (tokens.size() < 4 || output.size() >= 8) return;
        int height = medianHeight(tokens);
        // Independent side-by-side tables require a gutter and tabular evidence on BOTH sides.
        if (depth < 3) {
            List<Token> horizontal = new ArrayList<>(tokens);
            horizontal.sort(Comparator.comparingInt(token -> token.box.left));
            List<Integer> gutters = new ArrayList<>();
            int covered = horizontal.get(0).box.right;
            for (int i = 1; i < horizontal.size(); i++) {
                if (horizontal.get(i).box.left - covered > height * .5f) {
                    gutters.add(horizontal.get(i).box.left - covered);
                }
                covered = Math.max(covered, horizontal.get(i).box.right);
            }
            Collections.sort(gutters);
            float minimumGutter = Math.max(height * 3.5f,
                    gutters.isEmpty() ? Float.POSITIVE_INFINITY : gutters.get(gutters.size() / 2) * 1.8f);
            minimumGutter = Math.max(minimumGutter, tokenBounds(tokens).width() * .16f);
            int right = horizontal.get(0).box.right;
            for (int i = 1; i < horizontal.size(); i++) {
                Token token = horizontal.get(i);
                if (token.box.left - right > minimumGutter) {
                    int split = (right + token.box.left) / 2;
                    List<Token> left = new ArrayList<>(), remainder = new ArrayList<>();
                    for (Token word : tokens) (word.x() < split ? left : remainder).add(word);
                    if (tabular(left) && tabular(remainder)) {
                        splitCandidate(left, output, depth + 1);
                        splitCandidate(remainder, output, depth + 1);
                        return;
                    }
                }
                right = Math.max(right, token.box.right);
            }
        }
        if (!tabular(tokens)) return;
        // Single occupied cells can be merged headings or a final row with blank neighbours.
        // Region separation, rather than a cell-count filter, determines the first and last rows.
        output.add(new ArrayList<>(tokens));
    }

    private static boolean tabular(List<Token> tokens) {
        if (tokens.size() < 4) return false;
        int height = medianHeight(tokens);
        List<List<Token>> lines = lineGroups(tokens, Math.max(4, Math.round(height * .62f)));
        List<List<Integer>> anchors = new ArrayList<>();
        for (List<Token> line : lines) {
            List<Integer> starts = columnStarts(line, height);
            if (starts.size() >= 2) anchors.add(starts);
        }
        for (int a = 0; a < anchors.size(); a++) {
            for (int b = a + 1; b < anchors.size(); b++) {
                int matches = 0;
                for (int x : anchors.get(a)) {
                    for (int other : anchors.get(b)) {
                        if (Math.abs(x - other) <= height * 1.1f) { matches++; break; }
                    }
                }
                if (matches >= 2) return true;
            }
        }
        return false;
    }

    private static List<Integer> columnStarts(List<Token> line, int height) {
        List<Integer> result = new ArrayList<>();
        int right = Integer.MIN_VALUE;
        for (Token token : line) {
            if (result.isEmpty() || token.box.left - right > height * 1.05f) result.add(token.box.left);
            right = token.box.right;
        }
        return result;
    }

    private static boolean completeGeometricRows(List<Token> tokens) {
        int height = medianHeight(tokens);
        for (List<Token> line : lineGroups(tokens, Math.max(4, Math.round(height * .62f)))) {
            if (columnStarts(line, height).size() < 2) return false;
        }
        return true;
    }

    private static boolean collapsesRepeatedColumns(List<Token> tokens, TableStructureOcr.Result structure,
            OfficeTable geometric) {
        if (structure == null || structure.rows != geometric.rows || structure.columns >= geometric.columns) return false;
        for (TableStructureOcr.Cell cell : structure.cells) if (cell.height != 1 || cell.width != 1) return false;
        int height = medianHeight(tokens);
        List<List<android.graphics.Rect>> cells = new ArrayList<>();
        for (List<Token> line : lineGroups(tokens, Math.max(4, Math.round(height * .62f)))) {
            List<android.graphics.Rect> row = new ArrayList<>();
            android.graphics.Rect current = null;
            int right = 0;
            for (Token token : line) {
                if (current == null || token.box.left - right > height * 1.05f) {
                    current = new android.graphics.Rect(token.box);
                    row.add(current);
                } else current.union(token.box);
                right = token.box.right;
            }
            cells.add(row);
        }
        return stableColumnGutters(cells, geometric.columns, height);
    }

    /** At least two complete rows must share real, wide vertical whitespace and aligned column starts. */
    static boolean stableColumnGutters(List<List<android.graphics.Rect>> rows, int columns, int height) {
        for (int a = 0; a < rows.size(); a++) {
            if (rows.get(a).size() != columns) continue;
            for (int b = a + 1; b < rows.size(); b++) {
                if (rows.get(b).size() != columns) continue;
                boolean aligned = true;
                for (int c = 0; c < columns; c++) {
                    android.graphics.Rect first = rows.get(a).get(c), second = rows.get(b).get(c);
                    if (Math.abs(first.left - second.left) > height * 1.1f) { aligned = false; break; }
                    if (c + 1 < columns) {
                        int commonLeft = Math.max(first.right, second.right);
                        int commonRight = Math.min(rows.get(a).get(c + 1).left, rows.get(b).get(c + 1).left);
                        if (commonRight - commonLeft < height * 1.05f) { aligned = false; break; }
                    }
                }
                if (aligned) return true;
            }
        }
        return false;
    }

    private static int medianHeight(List<Token> tokens) {
        List<Integer> heights = new ArrayList<>();
        for (Token token : tokens) heights.add(token.box.height());
        Collections.sort(heights);
        return heights.isEmpty() ? 5 : Math.max(5, heights.get(heights.size() / 2));
    }

    private static android.graphics.Rect tokenBounds(List<Token> tokens) {
        android.graphics.Rect bounds = new android.graphics.Rect();
        for (Token token : tokens) bounds.union(token.box);
        return bounds;
    }

    private static OfficeTable structured(Bitmap bitmap, List<Token> tokens, TableStructureOcr.Result result,
            int page, int number) {
        if (result == null || result.confidence < .90f || result.rows < 2 || result.columns < 2) return null;
        List<List<Token>> assigned = new ArrayList<>();
        for (TableStructureOcr.Cell cell : result.cells) assigned.add(new ArrayList<>());
        int height = medianHeight(tokens);
        for (Token token : tokens) {
            int best = -1;
            float bestOverlap = -1, bestDistance = Float.POSITIVE_INFINITY;
            for (int i = 0; i < result.cells.size(); i++) {
                RectF cell = result.cells.get(i).bounds;
                float intersection = Math.max(0, Math.min(cell.right, token.box.right) - Math.max(cell.left, token.box.left))
                        * Math.max(0, Math.min(cell.bottom, token.box.bottom) - Math.max(cell.top, token.box.top));
                float union = cell.width() * cell.height() + token.box.width() * token.box.height() - intersection;
                float overlap = union > 0 ? intersection / union : 0;
                float distance = Math.abs(cell.left - token.box.left) + Math.abs(cell.top - token.box.top)
                        + Math.abs(cell.right - token.box.right) + Math.abs(cell.bottom - token.box.bottom);
                if (overlap > bestOverlap || overlap == bestOverlap && distance < bestDistance) {
                    best = i;
                    bestOverlap = overlap;
                    bestDistance = distance;
                }
            }
            if (best < 0) return null;
            RectF chosen = new RectF(result.cells.get(best).bounds);
            chosen.inset(-height * .85f, -height * .65f);
            if (!chosen.contains(token.x(), token.y())) return null;
            assigned.get(best).add(token);
        }
        int occupied = 0;
        Set<Integer> rows = new HashSet<>(), columns = new HashSet<>();
        List<OfficeTable.Cell> cells = new ArrayList<>();
        for (int i = 0; i < result.cells.size(); i++) {
            TableStructureOcr.Cell structure = result.cells.get(i);
            List<Token> content = assigned.get(i);
            if (!content.isEmpty()) {
                occupied++;
                rows.add(structure.row);
                columns.add(structure.column);
            }
            // Structure recognition only places original OCR strings; it does not rewrite numbers or names.
            OfficeTable.Cell cell = new OfficeTable.Cell(structure.row, structure.column,
                    structure.height, structure.width, words(content, null, null));
            RectF source = new RectF(structure.bounds);
            android.graphics.Rect text = tokenBounds(content);
            if (!text.isEmpty()) source.union(new RectF(text));
            cell.sourceBounds = new RectF(Math.max(0, source.left / bitmap.getWidth()),
                    Math.max(0, source.top / bitmap.getHeight()), Math.min(1, source.right / bitmap.getWidth()),
                    Math.min(1, source.bottom / bitmap.getHeight()));
            cells.add(cell);
        }
        if (occupied < 4 || rows.size() < 2 || columns.size() < 2) return null;
        return new OfficeTable("第 " + page + " 页表 " + number, page, result.rows, result.columns, true, cells);
    }

    private static OfficeTable supplement(Bitmap bitmap, List<Token> tokens, int page) {
        List<List<Token>> lines = lineGroups(tokens, Math.max(4, Math.round(medianHeight(tokens) * .62f)));
        List<OfficeTable.Cell> cells = new ArrayList<>();
        // The remaining text can include long notes; retain it without exceeding the editor row limit.
        int count = Math.min(OfficeTable.MAX_ROWS, lines.size());
        for (int r = 0; r < count; r++) {
            List<Token> content = new ArrayList<>(lines.get(r));
            if (r == count - 1) for (int more = count; more < lines.size(); more++) content.addAll(lines.get(more));
            OfficeTable.Cell cell = new OfficeTable.Cell(r, 0, 1, 1, words(content, null, null));
            android.graphics.Rect source = tokenBounds(content);
            cell.sourceBounds = new RectF((float) source.left / bitmap.getWidth(), (float) source.top / bitmap.getHeight(),
                    (float) source.right / bitmap.getWidth(), (float) source.bottom / bitmap.getHeight());
            cells.add(cell);
        }
        return new OfficeTable("第 " + page + " 页补充文字", page, count, 1, true, cells);
    }

    private static OfficeTable grid(Mat ink, Mat horizontal, Mat vertical, Rect bounds, List<Token> tokens,
            ChineseOcr.Page ocr, int page, int number, List<List<android.graphics.Rect>> regions) throws IOException {
        Mat rowMask = horizontal.submat(bounds);
        Mat columnMask = vertical.submat(bounds);
        int width = bounds.width;
        int height = bounds.height;
        byte[] rowPixels = new byte[width * height];
        byte[] colPixels = new byte[width * height];
        try {
            rowMask.get(0, 0, rowPixels);
            columnMask.get(0, 0, colPixels);
        } finally {
            ScanImages.release(rowMask, columnMask);
        }
        int[] rowCoverage = new int[height];
        int[] columnCoverage = new int[width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = y * width + x;
                if (rowPixels[index] != 0) rowCoverage[y]++;
                if (colPixels[index] != 0) columnCoverage[x]++;
            }
        }
        List<Integer> ys = lines(rowCoverage, Math.round(width * 0.26f));
        List<Integer> xs = lines(columnCoverage, Math.round(height * 0.26f));
        if (ys.size() >= 3) {
            xs.removeIf(x -> !crossesAnyRow(colPixels, width, x, ys));
        }
        if (ys.size() < 3 || xs.size() < 3) return null;
        int rows = ys.size() - 1;
        int columns = xs.size() - 1;
        if (regions != null) {
            if (rows > OfficeTable.MAX_ROWS || columns > OfficeTable.MAX_COLUMNS) return null;
            List<android.graphics.Rect> bands = new ArrayList<>(rows);
            for (int r = 0; r < rows; r++) {
                bands.add(new android.graphics.Rect(bounds.x + xs.get(0), bounds.y + ys.get(r),
                        bounds.x + xs.get(columns), bounds.y + ys.get(r + 1)));
            }
            regions.add(bands);
            return null;
        }
        if (rows > OfficeTable.MAX_ROWS || columns > OfficeTable.MAX_COLUMNS) {
            throw new IOException("第 " + page + " 页表格过大，请裁切后分段识别");
        }
        if (ocr != null) {
            List<Token> inside = new ArrayList<>();
            for (Token token : tokens) {
                if (token.x() >= bounds.x && token.x() < bounds.x + bounds.width
                        && token.y() >= bounds.y && token.y() < bounds.y + bounds.height) inside.add(token);
            }
            int shortRows = 0;
            float minimum = medianHeight(inside) * .85f;
            for (int r = 1; r < ys.size(); r++) if (ys.get(r) - ys.get(r - 1) < minimum) shortRows++;
            // Text strokes can masquerade as rules on small source images. Such a grid is incomplete
            // evidence, and must not suppress the structure model for the entire region.
            if (shortRows >= Math.max(2, rows / 5)) return null;
        }
        GridGroups groups = new GridGroups(rows * columns);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < columns; c++) {
                int index = r * columns + c;
                if (c + 1 < columns && !hasBoundary(colPixels, width, height,
                        xs.get(c + 1), ys.get(r), ys.get(r + 1), true)) {
                    groups.join(index, index + 1);
                }
                if (r + 1 < rows && !hasBoundary(rowPixels, width, height,
                        ys.get(r + 1), xs.get(c), xs.get(c + 1), false)) {
                    groups.join(index, index + columns);
                }
            }
        }
        Map<Integer, CellArea> areas = new LinkedHashMap<>();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < columns; c++) {
                int group = groups.find(r * columns + c);
                CellArea area = areas.get(group);
                if (area == null) areas.put(group, new CellArea(r, c));
                else area.extend(r, c);
            }
        }
        CellArea[][] owners = new CellArea[rows][columns];
        List<CellArea> placed = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < columns; c++) {
                CellArea area = areas.get(groups.find(r * columns + c));
                if (!area.rectangular()) area = new CellArea(r, c);
                owners[r][c] = area;
                if (r == area.top && c == area.left) placed.add(area);
            }
        }
        int inside = 0;
        for (Token token : tokens) {
            int row = interval(ys, token.y() - bounds.y);
            int col = interval(xs, token.x() - bounds.x);
            if (row < 0 || col < 0) continue;
            owners[row][col].words.add(token);
            inside++;
        }
        if (inside < 2) return null;
        List<OfficeTable.Cell> cells = new ArrayList<>(placed.size());
        for (CellArea area : placed) {
            android.graphics.Rect interior = new android.graphics.Rect(
                    bounds.x + xs.get(area.left) + 2, bounds.y + ys.get(area.top) + 2,
                    bounds.x + xs.get(area.right + 1) - 1, bounds.y + ys.get(area.bottom + 1) - 1);
            OfficeTable.Cell cell = new OfficeTable.Cell(area.top, area.left, area.bottom - area.top + 1,
                    area.right - area.left + 1, gridWords(area.words, ocr, ink, interior));
            if (ocr == null && !area.words.isEmpty()) {
                cell.sourceBounds = new RectF(interior.left / (float) ink.cols(), interior.top / (float) ink.rows(),
                        interior.right / (float) ink.cols(), interior.bottom / (float) ink.rows());
                cell.type = OfficeTable.CellType.TEXT;
                List<Token> alternatives = new ArrayList<>();
                boolean hasAlternative = false;
                for (Token token : area.words) {
                    cell.needsReview |= token.line.uncertain;
                    hasAlternative |= !token.line.alternative.isEmpty();
                    alternatives.add(new Token(token.box,
                            token.line.alternative.isEmpty() ? token.text : token.line.alternative));
                }
                if (hasAlternative) {
                    String alternative = words(alternatives, null, interior);
                    if (!alternative.equals(cell.text)) cell.alternative = alternative;
                }
            }
            cells.add(cell);
        }
        return new OfficeTable("第 " + page + " 页表 " + number, page, rows, columns, false, cells);
    }

    private static List<Integer> lines(int[] coverage, int minimum) {
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < coverage.length;) {
            if (coverage[i] < minimum) {
                i++;
                continue;
            }
            int start = i;
            int best = i;
            while (i < coverage.length && coverage[i] >= minimum) {
                if (coverage[i] > coverage[best]) best = i;
                i++;
            }
            int position = coverage[best] == coverage[start] ? (start + i - 1) / 2 : best;
            if (result.isEmpty() || position - result.get(result.size() - 1) >= 7) result.add(position);
        }
        return result;
    }

    private static boolean crossesAnyRow(byte[] vertical, int width, int x, List<Integer> rows) {
        // Digits or characters aligned down a column add up in a projection, but their
        // stems stop inside each cell. A divider reaches both ends of at least one row,
        // even when neighbouring rows have merged cells.
        int left = Math.max(0, x - 3), right = Math.min(width - 1, x + 3);
        for (int r = 1; r < rows.size(); r++) {
            int top = rows.get(r - 1), span = rows.get(r) - top;
            int hits = 0, first = 16, last = 0;
            for (int i = 1; i < 16; i++) {
                int offset = (top + span * i / 16) * width;
                for (int col = left; col <= right; col++) {
                    if (vertical[offset + col] == 0) continue;
                    first = Math.min(first, i);
                    last = i;
                    hits++;
                    break;
                }
            }
            if (hits >= 10 && first <= 3 && last >= 13) return true;
        }
        return false;
    }

    private static boolean hasBoundary(byte[] pixels, int width, int height, int fixed,
            int start, int end, boolean vertical) {
        int hits = 0;
        for (int i = 1; i <= 9; i++) {
            int moving = start + (end - start) * i / 10;
            for (int offset = -3; offset <= 3; offset++) {
                int x = vertical ? fixed + offset : moving;
                int y = vertical ? moving : fixed + offset;
                if (x >= 0 && x < width && y >= 0 && y < height && pixels[y * width + x] != 0) {
                    hits++;
                    break;
                }
            }
        }
        return hits >= 4;
    }

    private static int interval(List<Integer> lines, int coordinate) {
        int found = Collections.binarySearch(lines, coordinate);
        int index = found >= 0 ? found : -found - 2;
        return index < 0 || index >= lines.size() - 1 ? -1 : index;
    }

    private static OfficeTable infer(List<Token> tokens, ChineseOcr.Page ocr, int page, Bitmap source) throws IOException {
        return infer(tokens, ocr, page, source, null);
    }

    private static OfficeTable infer(List<Token> tokens, ChineseOcr.Page ocr, int page, Bitmap source,
            Set<Token> retained) throws IOException {
        List<Integer> heights = new ArrayList<>();
        for (Token token : tokens) heights.add(token.box.height());
        Collections.sort(heights);
        int median = Math.max(5, heights.get(heights.size() / 2));
        List<List<Token>> rows = lineGroups(tokens, Math.max(4, Math.round(median * 0.62f)));
        List<List<List<Token>>> candidates = new ArrayList<>();
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (List<Token> line : rows) {
            List<List<Token>> cells = new ArrayList<>();
            List<Token> cell = null;
            int lastRight = 0;
            for (Token token : line) {
                if (cell == null || token.box.left - lastRight > median * 1.05) {
                    cell = new ArrayList<>();
                    cells.add(cell);
                }
                cell.add(token);
                lastRight = token.box.right;
            }
            if (cells.size() >= 2) {
                candidates.add(cells);
                counts.put(cells.size(), counts.getOrDefault(cells.size(), 0) + 1);
            }
        }
        int columnCount = 0;
        int frequency = 0;
        for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > frequency || entry.getValue() == frequency
                    && entry.getKey() > columnCount) {
                columnCount = entry.getKey();
                frequency = entry.getValue();
            }
        }
        if (frequency < 2) return null;
        if (columnCount > OfficeTable.MAX_COLUMNS || candidates.size() > OfficeTable.MAX_ROWS) {
            throw new IOException("第 " + page + " 页表格过大，请裁切后分段识别");
        }
        List<List<Token>> reference = null;
        for (List<List<Token>> row : candidates) {
            if (row.size() == columnCount) {
                reference = row;
                break;
            }
        }
        if (reference == null) return null;
        int[] anchors = new int[columnCount];
        for (int c = 0; c < columnCount; c++) anchors[c] = reference.get(c).get(0).box.left;
        List<OfficeTable.Cell> values = new ArrayList<>();
        int rowCount = 0;
        for (List<List<Token>> row : candidates) {
            String[] texts = new String[columnCount];
            android.graphics.Rect[] sources = new android.graphics.Rect[columnCount];
            for (List<Token> cell : row) {
                if (retained != null) retained.addAll(cell);
                int x = cell.get(0).box.left;
                int nearest = 0;
                for (int c = 1; c < columnCount; c++) {
                    if (Math.abs(x - anchors[c]) < Math.abs(x - anchors[nearest])) nearest = c;
                }
                String value = words(cell, ocr, null);
                texts[nearest] = texts[nearest] == null ? value : texts[nearest] + " " + value;
                if (sources[nearest] == null) sources[nearest] = tokenBounds(cell);
                else sources[nearest].union(tokenBounds(cell));
            }
            for (int c = 0; c < columnCount; c++) {
                OfficeTable.Cell value = new OfficeTable.Cell(rowCount, c, 1, 1, texts[c] == null ? "" : texts[c]);
                if (source != null && sources[c] != null) {
                    android.graphics.Rect bounds = sources[c];
                    value.sourceBounds = new RectF((float) bounds.left / source.getWidth(), (float) bounds.top / source.getHeight(),
                            (float) bounds.right / source.getWidth(), (float) bounds.bottom / source.getHeight());
                }
                values.add(value);
            }
            rowCount++;
        }
        return rowCount < 2 ? null : new OfficeTable("第 " + page + " 页表 1", page,
                rowCount, columnCount, true, values);
    }

    private static String words(List<Token> tokens, ChineseOcr.Page ocr, android.graphics.Rect cell) {
        if (tokens.isEmpty()) return "";
        StringBuilder output = new StringBuilder();
        int height = Math.max(3, tokens.get(0).box.height() / 2);
        for (List<Token> line : lineGroups(tokens, height)) {
            if (output.length() > 0) output.append('\n');
            StringBuilder text = new StringBuilder();
            android.graphics.Rect bounds = new android.graphics.Rect();
            Token previous = null;
            for (Token token : line) {
                if (previous != null && needsSpace(previous, token)) text.append(' ');
                text.append(token.text);
                bounds.union(token.box);
                previous = token;
            }
            output.append(ocr == null ? text : ocr.refine(text.toString(), bounds, cell));
        }
        return output.toString();
    }

    private static String gridWords(List<Token> tokens, ChineseOcr.Page ocr, Mat ink,
            android.graphics.Rect cell) {
        String original = words(tokens, ocr, cell);
        if (ocr == null) return original;
        android.graphics.Rect center = new android.graphics.Rect(cell);
        center.inset(Math.min(6, Math.max(2, cell.width() / 12)),
                Math.min(6, Math.max(2, cell.height() / 12)));
        if (center.isEmpty()) return original;
        // Measure the marks inside the ruling. An empty cell needs no model invocation.
        Mat patch = ink.submat(center.top, center.bottom, center.left, center.right);
        Rect marks;
        try {
            marks = Imgproc.boundingRect(patch);
        } finally {
            patch.release();
        }
        if (marks.width < 3 || marks.height < 5) return original;
        android.graphics.Rect glyphs = new android.graphics.Rect(center.left + marks.x, center.top + marks.y,
                center.left + marks.x + marks.width, center.top + marks.y + marks.height);
        android.graphics.Rect detected = new android.graphics.Rect();
        for (Token token : tokens) detected.union(token.box);
        int slack = Math.max(5, glyphs.height() / 3);
        boolean crossesCell = !tokens.isEmpty() && (detected.top < cell.top - slack
                || detected.bottom > cell.bottom + slack || detected.left < cell.left - slack
                || detected.right > cell.right + slack);
        boolean missesInk = tokens.isEmpty() || glyphs.left < detected.left - slack
                || glyphs.right > detected.right + slack || glyphs.top < detected.top - slack
                || glyphs.bottom > detected.bottom + slack;
        boolean numeric = original.matches("[+−-]?[0-9][0-9.,]*");
        boolean malformedNumber = numeric && OfficeExport.numeric(original, true) == null;
        boolean wideInteger = original.matches("[0-9]+")
                && marks.width > marks.height * (.75 * original.length() + .18);
        if (!crossesCell && original.contains("\n")) return original;
        if (!crossesCell && !missesInk && !malformedNumber && !wideInteger) return original;
        String verified = ocr.readTableCell(glyphs, center);
        if (verified.isEmpty()) return original;
        if (numeric) {
            // Preserve every digit and the sign, including identifiers with leading zeroes.
            String originalDigits = original.replace('−', '-').replaceAll("[^0-9+-]", "");
            String verifiedDigits = verified.replace('−', '-').replaceAll("[^0-9+-]", "");
            if (!originalDigits.equals(verifiedDigits)) return original;
            if (malformedNumber) return OfficeExport.numeric(verified, true) == null ? original : verified;
            // Allow an omitted written unit only if the original number itself is unchanged.
            String withoutUnits = verified.replaceAll("[\\p{sc=Han}\\s]", "");
            if (withoutUnits.equals(verified) || !withoutUnits.equals(original)) return original;
        }
        return verified;
    }

    private static boolean needsSpace(Token a, Token b) {
        if (a.text.isEmpty() || b.text.isEmpty()) return false;
        if (b.box.left - a.box.right > Math.min(a.box.height(), b.box.height()) * 0.35) return true;
        char left = a.text.charAt(a.text.length() - 1);
        char right = b.text.charAt(0);
        return left < 128 && right < 128 && Character.isLetter(left) && Character.isLetter(right);
    }

    private static List<List<Token>> lineGroups(List<Token> words, int tolerance) {
        List<Token> sorted = new ArrayList<>(words);
        sorted.sort(Comparator.comparingInt(Token::y).thenComparingInt(t -> t.box.left));
        List<List<Token>> lines = new ArrayList<>();
        List<Token> line = null;
        int baseline = 0;
        for (Token token : sorted) {
            if (line == null || token.y() - baseline > tolerance) {
                baseline = token.y();
                line = new ArrayList<>();
                lines.add(line);
            }
            line.add(token);
        }
        for (List<Token> row : lines) row.sort(Comparator.comparingInt(t -> t.box.left));
        return lines;
    }

    private static List<Token> tokens(Text text) {
        List<Token> words = new ArrayList<>();
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                for (Text.Element element : line.getElements()) {
                    android.graphics.Rect position = element.getBoundingBox();
                    if (position != null && !element.getText().isEmpty()) {
                        List<String> symbols = new ArrayList<>();
                        List<android.graphics.Rect> bounds = new ArrayList<>();
                        for (Text.Symbol symbol : element.getSymbols()) {
                            if (symbol.getBoundingBox() != null) {
                                symbols.add(symbol.getText());
                                bounds.add(new android.graphics.Rect(symbol.getBoundingBox()));
                            }
                        }
                        words.add(new Token(position, element.getText(), symbols, bounds));
                    }
                }
            }
        }
        return words;
    }

    /** Uses genuine symbol locations only; text always comes from indexed slices of the original element. */
    static List<ElementPart> splitElement(String text, List<String> symbols, List<android.graphics.Rect> boxes) {
        List<ElementPart> result = new ArrayList<>();
        if (symbols.size() < 2 || symbols.size() != boxes.size()
                || text.matches("[\\p{N}A-Za-z+−.,:/_-]+")) return result;
        List<Integer> starts = new ArrayList<>(), heights = new ArrayList<>();
        int offset = 0;
        for (int i = 0; i < symbols.size(); i++) {
            String symbol = symbols.get(i);
            if (symbol.isEmpty() || boxes.get(i).isEmpty()) return result;
            while (offset < text.length() && Character.isWhitespace(text.codePointAt(offset)))
                offset += Character.charCount(text.codePointAt(offset));
            if (!text.startsWith(symbol, offset)) return result;
            starts.add(offset);
            offset += symbol.length();
            heights.add(boxes.get(i).height());
        }
        while (offset < text.length() && Character.isWhitespace(text.codePointAt(offset)))
            offset += Character.charCount(text.codePointAt(offset));
        if (offset != text.length()) return result;
        Collections.sort(heights);
        int height = Math.max(3, heights.get(heights.size() / 2));
        int begin = 0;
        android.graphics.Rect bounds = new android.graphics.Rect(boxes.get(0));
        for (int i = 1; i < boxes.size(); i++) {
            android.graphics.Rect previous = boxes.get(i - 1), next = boxes.get(i);
            if (Math.abs(next.centerY() - previous.centerY()) > height * .65f || next.left < previous.left)
                return new ArrayList<>();
            if (next.left - previous.right > height * 1.05f) {
                int boundary = starts.get(i);
                result.add(new ElementPart(text.substring(begin, boundary), new android.graphics.Rect(bounds), begin, boundary));
                begin = boundary;
                bounds.setEmpty();
            }
            bounds.union(next);
        }
        if (!result.isEmpty()) result.add(new ElementPart(text.substring(begin), bounds, begin, text.length()));
        return result;
    }

    static final class ElementPart {
        final String text;
        final android.graphics.Rect bounds;
        final int start, end;

        ElementPart(String text, android.graphics.Rect bounds, int start, int end) {
            this.text = text;
            this.bounds = bounds;
            this.start = start;
            this.end = end;
        }
    }

    private static final class Token {
        final android.graphics.Rect box;
        final String text;
        final HandwritingOcr.Line line;
        final List<String> symbols;
        final List<android.graphics.Rect> symbolBounds;

        Token(android.graphics.Rect box, String text) {
            this(box, text, null);
        }

        Token(android.graphics.Rect box, String text, HandwritingOcr.Line line) {
            this.box = box;
            this.text = text;
            this.line = line;
            symbols = Collections.emptyList();
            symbolBounds = Collections.emptyList();
        }

        Token(android.graphics.Rect box, String text, List<String> symbols, List<android.graphics.Rect> symbolBounds) {
            this.box = box;
            this.text = text;
            this.line = null;
            this.symbols = symbols;
            this.symbolBounds = symbolBounds;
        }

        int x() { return box.centerX(); }
        int y() { return box.centerY(); }
    }

    private static final class Segment {
        final double angle;
        final double length;

        Segment(double angle, double length) {
            this.angle = angle;
            this.length = length;
        }
    }

    private static final class GridGroups {
        private final int[] parents;

        GridGroups(int size) {
            parents = new int[size];
            for (int i = 0; i < size; i++) parents[i] = i;
        }

        int find(int index) {
            while (parents[index] != index) {
                parents[index] = parents[parents[index]];
                index = parents[index];
            }
            return index;
        }

        void join(int a, int b) {
            parents[find(b)] = find(a);
        }
    }

    private static final class CellArea {
        int top;
        int left;
        int right;
        int bottom;
        int size = 1;
        final List<Token> words = new ArrayList<>();

        CellArea(int row, int col) {
            top = bottom = row;
            left = right = col;
        }

        void extend(int row, int col) {
            top = Math.min(top, row);
            bottom = Math.max(bottom, row);
            left = Math.min(left, col);
            right = Math.max(right, col);
            size++;
        }

        boolean rectangular() {
            return size == (bottom - top + 1) * (right - left + 1);
        }
    }
}
