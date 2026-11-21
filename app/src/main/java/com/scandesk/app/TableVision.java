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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
                if (table != null) tables.add(table);
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
        } else if (regions == null && tables.isEmpty()) {
            OfficeTable inferred = infer(tokens, ocr, page);
            if (inferred != null) tables.add(inferred);
        }
        return tables;
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

    private static OfficeTable infer(List<Token> tokens, ChineseOcr.Page ocr, int page) throws IOException {
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
            for (List<Token> cell : row) {
                int x = cell.get(0).box.left;
                int nearest = 0;
                for (int c = 1; c < columnCount; c++) {
                    if (Math.abs(x - anchors[c]) < Math.abs(x - anchors[nearest])) nearest = c;
                }
                String value = words(cell, ocr, null);
                texts[nearest] = texts[nearest] == null ? value : texts[nearest] + " " + value;
            }
            for (int c = 0; c < columnCount; c++) {
                values.add(new OfficeTable.Cell(rowCount, c, 1, 1, texts[c] == null ? "" : texts[c]));
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
                        words.add(new Token(position, element.getText()));
                    }
                }
            }
        }
        return words;
    }

    private static final class Token {
        final android.graphics.Rect box;
        final String text;
        final HandwritingOcr.Line line;

        Token(android.graphics.Rect box, String text) {
            this(box, text, null);
        }

        Token(android.graphics.Rect box, String text, HandwritingOcr.Line line) {
            this.box = box;
            this.text = text;
            this.line = line;
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
