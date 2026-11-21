package com.scandesk.app;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;

import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** PaddleX SLANet_plus preprocessing and HTML decoder; see assets/licenses/slanet-plus.txt. */
final class TableStructureOcr implements AutoCloseable {
    static final int EDGE = 488;
    private static final float[] MEAN = {.485f, .456f, .406f};
    private static final float[] STD = {.229f, .224f, .225f};
    private static final Pattern SPAN = Pattern.compile("(rowspan|colspan)=\"([0-9]+)\"");
    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
    private final OrtSession session;
    private final List<String> alphabet = new ArrayList<>();

    TableStructureOcr(AssetManager assets) throws IOException, OrtException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                assets.open("vision/slanet-plus-tokens.txt"), StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) alphabet.add(line);
        }
        if (alphabet.size() != 50 || !alphabet.get(0).equals("sos") || !alphabet.get(49).equals("eos")) {
            throw new IOException("表格结构字典损坏");
        }
        session = ChineseOcr.openSession(assets, environment, "vision/slanet-plus.onnx");
    }

    Result recognize(Bitmap bitmap, Rect requested) throws IOException {
        checkInterrupted();
        Rect region = new Rect(requested);
        if (!region.intersect(0, 0, bitmap.getWidth(), bitmap.getHeight()) || region.width() < 20
                || region.height() < 20) return null;
        float ratio = (float) EDGE / Math.max(region.width(), region.height());
        float[] input = preprocess(bitmap, region);
        checkInterrupted();
        try (OnnxTensor tensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(input),
                new long[]{1, 3, EDGE, EDGE});
                OrtSession.Result output = session.run(Collections.singletonMap("x", tensor))) {
            OnnxTensor boxes = (OnnxTensor) output.get(0);
            OnnxTensor probabilities = (OnnxTensor) output.get(1);
            long[] shape = probabilities.getInfo().getShape();
            if (shape.length != 3 || shape[0] != 1 || shape[2] != alphabet.size()
                    || boxes.getInfo().getShape()[2] != 8) throw new IOException("表格结构模型输出不兼容");
            FloatBuffer scores = probabilities.getFloatBuffer();
            FloatBuffer quads = boxes.getFloatBuffer();
            List<String> tokens = new ArrayList<>();
            List<RectF> bounds = new ArrayList<>();
            float sum = 0;
            for (int step = 0; step < shape[1]; step++) {
                if ((step & 31) == 0) checkInterrupted();
                int best = 0;
                for (int c = 1; c < alphabet.size(); c++) {
                    if (scores.get(step * alphabet.size() + c) > scores.get(step * alphabet.size() + best)) best = c;
                }
                if (best == alphabet.size() - 1) break;
                if (best == 0) continue;
                String token = alphabet.get(best);
                tokens.add(token);
                sum += scores.get(step * alphabet.size() + best);
                if (token.equals("<td") || token.equals("<td>") || token.equals("<td></td>")) {
                    RectF box = new RectF(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                            Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY);
                    for (int point = 0; point < 4; point++) {
                        float x = quads.get(step * 8 + point * 2) * EDGE / ratio + region.left;
                        float y = quads.get(step * 8 + point * 2 + 1) * EDGE / ratio + region.top;
                        if (!Float.isFinite(x) || !Float.isFinite(y)) return null;
                        box.left = Math.min(box.left, x);
                        box.right = Math.max(box.right, x);
                        box.top = Math.min(box.top, y);
                        box.bottom = Math.max(box.bottom, y);
                    }
                    if (!box.intersect(region.left, region.top, region.right, region.bottom)
                            || box.width() < 1 || box.height() < 1) return null;
                    bounds.add(box);
                }
            }
            return decode(tokens, bounds, tokens.isEmpty() ? 0 : sum / tokens.size());
        } catch (OrtException failure) {
            throw new IOException("表格结构识别失败", failure);
        }
    }

    static float[] preprocess(Bitmap bitmap, Rect region) throws IOException {
        ScanImages.requireVision();
        Mat rgba = new Mat();
        Mat bgr = new Mat();
        Mat resized = new Mat();
        Mat crop = null;
        try {
            Utils.bitmapToMat(bitmap, rgba);
            crop = rgba.submat(region.top, region.bottom, region.left, region.right);
            Imgproc.cvtColor(crop, bgr, Imgproc.COLOR_RGBA2BGR);
            double ratio = (double) EDGE / Math.max(region.width(), region.height());
            int width = Math.max(1, (int) Math.round(region.width() * ratio));
            int height = Math.max(1, (int) Math.round(region.height() * ratio));
            Imgproc.resize(bgr, resized, new Size(width, height), 0, 0, Imgproc.INTER_LINEAR);
            byte[] pixels = new byte[width * height * 3];
            resized.get(0, 0, pixels);
            // Upstream pads AFTER normalization. The right/bottom padding must remain zero.
            float[] input = new float[3 * EDGE * EDGE];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    for (int c = 0; c < 3; c++) {
                        input[(c * EDGE + y) * EDGE + x] =
                                ((pixels[(y * width + x) * 3 + c] & 255) / 255f - MEAN[c]) / STD[c];
                    }
                }
            }
            return input;
        } finally {
            if (crop != null) crop.release();
            ScanImages.release(rgba, bgr, resized);
        }
    }

    /** Decodes spans by occupying the logical grid, including rows covered by earlier rowspans. */
    static Result decode(List<String> tokens, List<RectF> boxes, float confidence) {
        List<Cell> cells = new ArrayList<>();
        boolean[][] occupied = new boolean[OfficeTable.MAX_ROWS][OfficeTable.MAX_COLUMNS];
        int row = -1, column = 0, columns = 0, boxIndex = 0;
        boolean inRow = false, inCell = false;
        StringBuilder opening = null;
        for (String token : tokens) {
            if (token.equals("<tr>")) {
                if (inRow || opening != null || ++row >= occupied.length) return null;
                inRow = true;
                column = 0;
            } else if (token.equals("</tr>")) {
                if (!inRow || opening != null || inCell) return null;
                inRow = false;
            } else if (token.equals("<td") || token.equals("<td>") || token.equals("<td></td>")) {
                if (!inRow || opening != null || inCell || boxIndex >= boxes.size()) return null;
                opening = new StringBuilder(token);
                if (token.equals("<td")) continue;
            } else if (opening != null) {
                opening.append(token);
                if (!token.equals(">")) continue;
            } else if (token.equals("</td>")) {
                if (!inCell) return null;
                inCell = false;
            }
            if (opening == null) continue;
            int height = 1, width = 1;
            Matcher span = SPAN.matcher(opening);
            while (span.find()) {
                int count;
                try { count = Integer.parseInt(span.group(2)); }
                catch (NumberFormatException invalid) { return null; }
                if (span.group(1).equals("rowspan")) height = count;
                else width = count;
            }
            while (column < occupied[row].length && occupied[row][column]) column++;
            if (height < 1 || width < 1 || row + height > occupied.length
                    || column + width > occupied[row].length) return null;
            for (int r = row; r < row + height; r++) {
                for (int c = column; c < column + width; c++) {
                    if (occupied[r][c]) return null;
                    occupied[r][c] = true;
                }
            }
            cells.add(new Cell(row, column, height, width, new RectF(boxes.get(boxIndex++))));
            columns = Math.max(columns, column + width);
            column += width;
            inCell = !opening.toString().equals("<td></td>");
            opening = null;
        }
        int rows = row + 1;
        if (inRow || inCell || opening != null || rows < 2 || columns < 2 || cells.isEmpty()
                || boxIndex != boxes.size() || !Float.isFinite(confidence)) return null;
        for (Cell cell : cells) if (cell.row + cell.height > rows) return null;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < columns; c++) if (!occupied[r][c]) return null;
        }
        return new Result(rows, columns, cells, confidence);
    }

    private static void checkInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("已取消");
    }

    @Override public void close() throws OrtException {
        session.close();
    }

    static final class Result {
        final int rows, columns;
        final List<Cell> cells;
        final float confidence;

        Result(int rows, int columns, List<Cell> cells, float confidence) {
            this.rows = rows;
            this.columns = columns;
            this.cells = Collections.unmodifiableList(cells);
            this.confidence = confidence;
        }
    }

    static final class Cell {
        final int row, column, height, width;
        final RectF bounds;

        Cell(int row, int column, int height, int width, RectF bounds) {
            this.row = row;
            this.column = column;
            this.height = height;
            this.width = width;
            this.bounds = bounds;
        }
    }
}
