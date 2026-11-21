package com.scandesk.app;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.RectF;

import com.paddle.ocr.model.OCRBox;
import com.paddle.ocr.postprocess.BoxSorter;
import com.paddle.ocr.postprocess.DBPostProcessor;
import com.paddle.ocr.postprocess.QuadTextCrop;
import com.paddle.ocr.preprocess.DetPreprocessResult;
import com.paddle.ocr.preprocess.DetPreprocessor;
import com.paddle.ocr.util.BitmapUtils;

import org.opencv.core.Mat;
import org.opencv.core.CvType;
import org.opencv.core.Size;
import org.opencv.core.Rect;
import kotlin.Pair;
import org.opencv.imgproc.Imgproc;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.Map;

/** On-device PaddleOCR text detection, rectification and CTC reading for photographed handwriting. */
final class HandwritingOcr implements AutoCloseable {
    private final OrtEnvironment environment;
    private final AssetManager assets;
    private final OrtSession detector;
    private final ChineseOcr recognizer;
    private ChineseOcr preciseRecognizer;

    HandwritingOcr(AssetManager assets) throws IOException {
        this.assets = assets;
        ScanImages.requireVision();
        try {
            environment = OrtEnvironment.getEnvironment();
            detector = ChineseOcr.openSession(assets, environment, "ocr/ppocrv5-mobile-det.onnx");
        } catch (OrtException cause) {
            throw new IOException("无法加载手写检测模型", cause);
        }
        recognizer = new ChineseOcr(assets);
    }

    String page(Bitmap bitmap, List<ScanRecognition.NumberLine> numbers) throws IOException {
        Mat source = BitmapUtils.INSTANCE.bitmapToBGRMat(bitmap);
        try {
            List<OCRBox> rows = BoxSorter.INSTANCE.sortInReadingOrder(detect(source));
            List<String> text = new ArrayList<>(rows.size());
            for (OCRBox row : rows) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("已取消");
                Mat crop = QuadTextCrop.INSTANCE.crop(source, row);
                String result;
                try {
                    result = readLine(crop);
                } finally {
                    crop.release();
                }
                if (result.isEmpty()) continue;
                text.add(result);
                if (ScanRecognition.NumberLine.hasDigit(result)) {
                    RectF bounds = bounds(row, bitmap.getWidth(), bitmap.getHeight());
                    if (!bounds.isEmpty()) numbers.add(new ScanRecognition.NumberLine(result, bounds));
                }
            }
            return String.join("\n", text);
        } catch (OrtException cause) {
            throw new IOException("手写识别失败，请重试", cause);
        } finally {
            source.release();
        }
    }

    /** The same coordinates as the supplied corrected scan, so review can show the exact source item. */
    static final class Line {
        final String text;
        final String alternative;
        final RectF bounds;
        final boolean uncertain;

        Line(String text, String alternative, RectF bounds, boolean uncertain) {
            this.text = text;
            this.alternative = alternative;
            this.bounds = new RectF(bounds);
            this.uncertain = uncertain;
        }
    }

    List<Line> tableLines(Bitmap bitmap, Runnable checkpoint) throws IOException {
        checkpoint.run();
        if (preciseRecognizer == null) preciseRecognizer = new ChineseOcr(assets, "ppocrv5-server-rec.onnx");
        Mat source = BitmapUtils.INSTANCE.bitmapToBGRMat(bitmap);
        try {
            Map<OCRBox, OCRBox> fullPageBoxes = new IdentityHashMap<>();
            List<OCRBox> boxes = detailedBoxes(source, checkpoint, fullPageBoxes);
            List<Line> lines = new ArrayList<>(boxes.size());
            for (OCRBox box : BoxSorter.INSTANCE.sortInReadingOrder(boxes)) {
                checkpoint.run();
                if (Thread.currentThread().isInterrupted()) throw new IOException("已取消");
                Mat crop = QuadTextCrop.INSTANCE.crop(source, box);
                Mat clean = new Mat();
                Mat compact = new Mat();
                Mat comparison = null;
                Mat comparisonClean = new Mat();
                try {
                    Mat input = normalizeLightStrokes(crop, clean) ? clean : crop;
                    if (input.cols() > 32L * input.rows()) {
                        Imgproc.resize(input, compact, new Size(1536, 48), 0, 0, Imgproc.INTER_AREA);
                        input = compact;
                    }
                    Pair<String, Float> precise = preciseRecognizer.handwritingResult(input, true);
                    // Compare an independently located full-page line when available. Two readers of
                    // the same clipped crop can otherwise agree on the same missing or joined stroke.
                    OCRBox reference = fullPageBoxes.get(box);
                    Mat second = input;
                    if (reference != null && reference != box) {
                        comparison = QuadTextCrop.INSTANCE.crop(source, reference);
                        second = normalizeLightStrokes(comparison, comparisonClean) ? comparisonClean : comparison;
                        if (second.cols() > 32L * second.rows()) {
                            Imgproc.resize(second, comparisonClean, new Size(1536, 48), 0, 0, Imgproc.INTER_AREA);
                            second = comparisonClean;
                        }
                    }
                    Pair<String, Float> fast = recognizer.handwritingResult(second, true);
                    String text = precise.getFirst().trim();
                    String alternative = fast.getFirst().trim();
                    boolean agrees = text.replaceAll("\\s+", "").equals(alternative.replaceAll("\\s+", ""));
                    boolean uncertain = !agrees || precise.getSecond() < .92f || fast.getSecond() < .88f;
                    // A located item remains reviewable even if neither model can read it.
                    if (text.isEmpty()) {
                        text = alternative;
                        alternative = "";
                        uncertain = true;
                    }
                    lines.add(new Line(text, agrees ? "" : alternative,
                            bounds(box, bitmap.getWidth(), bitmap.getHeight()), uncertain));
                } finally {
                    crop.release();
                    clean.release();
                    compact.release();
                    if (comparison != null) comparison.release();
                    comparisonClean.release();
                }
            }
            return lines;
        } catch (OrtException failure) {
            throw new IOException("手写表格识别失败，请重试", failure);
        } finally {
            source.release();
        }
    }

    private List<OCRBox> detailedBoxes(Mat source, Runnable checkpoint, Map<OCRBox, OCRBox> references)
            throws IOException, OrtException {
        // Bound each core by pixels, independent of the document's contents or number of columns.
        int columns = Math.max(1, (source.cols() + 899) / 900);
        int rows = Math.max(1, (source.rows() + 899) / 900);
        if (columns == 1 && rows == 1) return detect(source);
        List<OCRBox> result = new ArrayList<>();
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < columns; x++) {
                checkpoint.run();
                if (Thread.currentThread().isInterrupted()) throw new IOException("已取消");
                int left = x * source.cols() / columns, right = (x + 1) * source.cols() / columns;
                int top = y * source.rows() / rows, bottom = (y + 1) * source.rows() / rows;
                int marginX = Math.min(96, Math.round((right - left) * .125f));
                int marginY = Math.min(96, Math.round((bottom - top) * .125f));
                Rect window = new Rect(Math.max(0, left - marginX), Math.max(0, top - marginY),
                        Math.min(source.cols(), right + marginX) - Math.max(0, left - marginX),
                        Math.min(source.rows(), bottom + marginY) - Math.max(0, top - marginY));
                Mat tile = source.submat(window);
                try {
                    for (OCRBox box : detect(tile)) {
                        List<PointF> points = new ArrayList<>(4);
                        float cx = 0, cy = 0;
                        for (PointF p : box.getPoints()) {
                            PointF point = new PointF(p.x + window.x, p.y + window.y);
                            points.add(point);
                            cx += point.x / 4;
                            cy += point.y / 4;
                        }
                        if (cx >= left && cx < right && cy >= top && cy < bottom) {
                            result.add(new OCRBox(points));
                        }
                    }
                } finally {
                    tile.release();
                }
            }
        }
        // Recover complete lines crossing tile seams. Prefer the full-page box when several
        // overlapping fragments describe one line; keep separated neighbouring columns independent.
        for (OCRBox box : detect(source)) {
            checkpoint.run();
            RectF full = bounds(box, source.cols(), source.rows());
            List<OCRBox> matches = new ArrayList<>();
            for (OCRBox local : result) {
                RectF part = bounds(local, source.cols(), source.rows());
                RectF intersection = new RectF(full);
                if (intersection.intersect(part) && intersection.width() * intersection.height()
                        > .55f * Math.min(full.width() * full.height(), part.width() * part.height())
                        && intersection.height() > .5f * Math.min(full.height(), part.height())) {
                    matches.add(local);
                }
            }
            boolean fragmented = false;
            if (matches.size() == 1) {
                RectF part = bounds(matches.get(0), source.cols(), source.rows());
                fragmented = part.width() < full.width() * .75f;
            } else if (matches.size() > 1) {
                for (int i = 0; i < matches.size(); i++) {
                    RectF a = bounds(matches.get(i), source.cols(), source.rows());
                    for (int j = i + 1; j < matches.size(); j++) {
                        RectF b = bounds(matches.get(j), source.cols(), source.rows());
                        RectF overlap = new RectF(a);
                        if (overlap.intersect(b) && overlap.width() > .10f * Math.min(a.width(), b.width())
                                && overlap.height() > .5f * Math.min(a.height(), b.height())) fragmented = true;
                    }
                }
            }
            if (matches.isEmpty() || fragmented) {
                result.removeAll(matches);
                result.add(box);
            } else {
                for (OCRBox match : matches) references.put(match, box);
            }
        }
        return result;
    }

    private String readLine(Mat crop) throws IOException, OrtException {
        Mat compact = new Mat();
        Mat clean = new Mat();
        try {
            // Bound the vocabulary-by-timestep output for extremely slender lines without dropping the end.
            if (crop.cols() > 32L * crop.rows()) {
                Imgproc.resize(crop, compact, new Size(1536, 48), 0, 0, Imgproc.INTER_AREA);
                crop = compact;
            }
            return recognizer.handwriting(normalizeLightStrokes(crop, clean) ? clean : crop);
        } finally {
            compact.release();
            clean.release();
        }
    }

    // A dark-frame light-ink line (e.g. chalk) is rectified before recognition; the saved scan stays untouched.
    private static boolean normalizeLightStrokes(Mat crop, Mat clean) {
        Mat thumbnail = new Mat(), gray = new Mat();
        try {
            Imgproc.resize(crop, thumbnail, new Size(Math.min(160, crop.cols()), Math.min(48, crop.rows())),
                    0, 0, Imgproc.INTER_AREA);
            Imgproc.cvtColor(thumbnail, gray, Imgproc.COLOR_BGR2GRAY);
            int w = gray.cols(), h = gray.rows();
            byte[] pixels = new byte[w * h];
            gray.get(0, 0, pixels);
            int border = 0, borderDark = 0;
            int[] histogram = new int[256];
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                int value = pixels[y * w + x] & 255;
                histogram[value]++;
                if (y < 2 || y >= h - 2 || x < 2 || x >= w - 2) {
                    border++;
                    if (value < 106) borderDark++;
                }
            }
            if (borderDark < .75 * border) return false;
            int background = percentile(histogram, pixels.length, .25f);
            int stroke = percentile(histogram, pixels.length, .97f);
            if (background >= 100 || stroke < 140 || stroke - background < 65) return false;
            Imgproc.cvtColor(crop, gray, Imgproc.COLOR_BGR2GRAY);
            double scale = 255.0 / (stroke - background);
            gray.convertTo(gray, CvType.CV_8U, -scale, stroke * scale);
            Imgproc.cvtColor(gray, clean, Imgproc.COLOR_GRAY2BGR);
            return true;
        } finally {
            gray.release();
            thumbnail.release();
        }
    }

    private static int percentile(int[] histogram, int total, float fraction) {
        int target = (int) Math.ceil(total * fraction), count = 0;
        for (int i = 0; i < histogram.length; i++) {
            count += histogram[i];
            if (count >= target) return i;
        }
        return 255;
    }

    private List<OCRBox> detect(Mat source) throws OrtException, IOException {
        DetPreprocessResult data = DetPreprocessor.INSTANCE.preprocess(source, 960, "resize_long", 960, "BGR");
        try (OnnxTensor input = OnnxTensor.createTensor(environment, FloatBuffer.wrap(data.getTensorData()),
                    data.getShape());
                OrtSession.Result result = detector.run(Collections.singletonMap("x", input))) {
            OnnxTensor output = (OnnxTensor) result.get(0);
            long[] shape = output.getInfo().getShape();
            if (shape.length != 4 || shape[0] != 1 || shape[1] != 1) throw new IOException("手写检测结果无效");
            FloatBuffer buffer = output.getFloatBuffer();
            float[] scores = new float[buffer.remaining()];
            buffer.get(scores);
            return DBPostProcessor.INSTANCE.process(scores, shape, .3f, .6f, 1.5f, 1000, false, "fast", "quad",
                    source.rows(), source.cols());
        }
    }

    private static RectF bounds(OCRBox box, int width, int height) {
        RectF rect = new RectF(1, 1, 0, 0);
        for (PointF point : box.getPoints()) {
            float x = Math.max(0, Math.min(1, point.x / width));
            float y = Math.max(0, Math.min(1, point.y / height));
            rect.left = Math.min(rect.left, x);
            rect.right = Math.max(rect.right, x);
            rect.top = Math.min(rect.top, y);
            rect.bottom = Math.max(rect.bottom, y);
        }
        return rect;
    }

    @Override public void close() throws IOException {
        try {
            detector.close();
        } catch (OrtException cause) {
            throw new IOException("无法释放手写检测模型", cause);
        } finally {
            try {
                recognizer.close();
            } finally {
                if (preciseRecognizer != null) preciseRecognizer.close();
            }
        }
    }
}
