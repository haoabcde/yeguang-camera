package com.scandesk.app;

import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.Log;

import com.paddle.ocr.postprocess.CTCDecoder;
import com.paddle.ocr.preprocess.RecPreprocessResult;
import com.paddle.ocr.preprocess.RecPreprocessor;
import org.opencv.core.Mat;
import kotlin.Pair;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtLoggingLevel;
import ai.onnxruntime.OrtSession;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ChineseOcr implements AutoCloseable {
    private static final String TAG = "ChineseOcr";
    private static final int HEIGHT = 48;
    private static final int MIN_WIDTH = 160;
    private static final int MAX_WIDTH = 2560;
    private static final int CLASS_COUNT = 18385;
    private static final float MIN_HAN_CONFIDENCE = .88f;
    private static final float MEAN_HAN_CONFIDENCE = .94f;
    private static final Pattern HAN_RUN = Pattern.compile("\\p{sc=Han}+(?:[\\p{Zs}\\t]+\\p{sc=Han}+)*");
    private static final Pattern SPACE = Pattern.compile("[\\p{Zs}\\t]+");

    private final AssetManager assets;
    private final String model;
    private final Paint filter = new Paint(Paint.FILTER_BITMAP_FLAG);
    private OrtEnvironment environment;
    private OrtSession session;
    private List<String> alphabet;
    private boolean unavailable;
    private TableStructureOcr tableStructure;
    private boolean structureUnavailable;

    ChineseOcr(AssetManager assets) {
        this(assets, "ppocrv5-mobile-rec.onnx");
    }

    ChineseOcr(AssetManager assets, String model) {
        this.assets = assets;
        this.model = model;
    }

    Page on(Bitmap bitmap) {
        return new Page(bitmap);
    }

    final class Page {
        private final Bitmap bitmap;

        private Page(Bitmap bitmap) {
            this.bitmap = bitmap;
        }

        TableStructureOcr.Result structure(Rect region) throws IOException {
            if (Thread.currentThread().isInterrupted()) throw new IOException("已取消");
            if (structureUnavailable) return null;
            try {
                if (tableStructure == null) tableStructure = new TableStructureOcr(assets);
                return tableStructure.recognize(bitmap, region);
            } catch (IOException | OrtException | RuntimeException | LinkageError failure) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("已取消", failure);
                structureUnavailable = true;
                Log.w(TAG, "Table structure recognition unavailable; retaining geometry", failure);
                return null;
            }
        }

        String refine(String text, Rect bounds, Rect cell) {
            if (unavailable || bounds == null || bounds.isEmpty() || !HAN_RUN.matcher(text).find()
                    || Thread.currentThread().isInterrupted()) return text;
            Rect region = new Rect(bounds);
            region.inset(-Math.max(2, Math.round(bounds.height() * .18f)),
                    -Math.max(2, Math.round(bounds.height() * .17f)));
            if (!region.intersect(0, 0, bitmap.getWidth(), bitmap.getHeight())
                    || cell != null && (cell.isEmpty() || !region.intersect(cell))) return text;
            String candidate = readRegion(region, false);
            return candidate.isEmpty() ? text : mergeHan(text, candidate);
        }

        String readTableCell(Rect ink, Rect interior) {
            if (unavailable || Thread.currentThread().isInterrupted()) return "";
            Rect region = new Rect(ink);
            int pad = Math.max(2, Math.round(ink.height() * .18f));
            region.inset(-pad, -pad);
            if (!region.intersect(interior)) return "";
            return readRegion(region, true);
        }

        RecognitionQuality.Sample verifyLine(String original, Rect bounds) throws IOException {
            if (Thread.currentThread().isInterrupted()) throw new IOException("已取消");
            if (bounds == null || bounds.isEmpty()) {
                return new RecognitionQuality.Sample(original, Float.NaN, "", Float.NaN);
            }
            Rect area = new Rect(bounds);
            int padding = Math.max(2, Math.round(bounds.height() * .10f));
            area.inset(-padding, -padding);
            if (!area.intersect(0, 0, bitmap.getWidth(), bitmap.getHeight())) {
                return new RecognitionQuality.Sample(original, Float.NaN, "", Float.NaN);
            }
            ScanImages.requireVision();
            Bitmap section = Bitmap.createBitmap(bitmap, area.left, area.top, area.width(), area.height());
            Mat crop = null;
            try {
                crop = com.paddle.ocr.util.BitmapUtils.INSTANCE.bitmapToBGRMat(section);
                Pair<String, Float> result = handwritingResult(crop, true);
                String comparison = result.getFirst().trim();
                float support = result.getSecond() * RecognitionQuality.agreement(original, comparison);
                return new RecognitionQuality.Sample(original, support, comparison, result.getSecond());
            } catch (OrtException failure) {
                throw new IOException("无法复核识别结果，请重试", failure);
            } finally {
                if (crop != null) crop.release();
                if (section != bitmap) section.recycle();
            }
        }

        private String readRegion(Rect region, boolean isolatedCell) {
            int contentWidth = (int) Math.ceil((double) HEIGHT * region.width() / region.height());
            if (contentWidth > MAX_WIDTH) return "";
            try {
                if (session == null) loadModel();
                return recognize(bitmap, region, contentWidth, isolatedCell);
            } catch (IOException | OrtException | RuntimeException | LinkageError failure) {
                unavailable = true;
                Log.w(TAG, "Chinese text refinement unavailable", failure);
                return "";
            }
        }
    }

    private void loadModel() throws IOException, OrtException {
        List<String> labels = new ArrayList<>(CLASS_COUNT);
        labels.add(""); // CTC blank
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                assets.open("ocr/ppocrv5-characters.txt"), StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null; ) labels.add(line);
        }
        if (labels.size() != CLASS_COUNT) throw new IOException("Unexpected recognition alphabet size");
        environment = OrtEnvironment.getEnvironment();
        session = openSession(assets, environment, "ocr/" + model);
        alphabet = labels;
    }

    static OrtSession openSession(AssetManager assets, OrtEnvironment environment, String filename)
            throws IOException, OrtException {
        try (AssetFileDescriptor asset = assets.openFd(filename);
                FileInputStream stream = asset.createInputStream();
                OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            ByteBuffer model = stream.getChannel().map(FileChannel.MapMode.READ_ONLY,
                    asset.getStartOffset(), asset.getLength());
            options.setIntraOpNumThreads(2);
            options.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR);
            return environment.createSession(model, options);
        }
    }

    String handwriting(Mat crop) throws IOException, OrtException {
        Pair<String, Float> result = handwritingResult(crop, false);
        return result.getSecond() >= .50f ? result.getFirst().trim() : "";
    }

    Pair<String, Float> handwritingResult(Mat crop, boolean pad) throws IOException, OrtException {
        if (session == null) loadModel();
        RecPreprocessResult line = RecPreprocessor.INSTANCE.preprocessBatch(Collections.singletonList(crop));
        float[] values = line.getTensorData();
        long[] dimensions = line.getShape();
        int width = (int) dimensions[3];
        if (pad && width < 320) {
            float[] padded = new float[3 * HEIGHT * 320];
            for (int c = 0; c < 3; c++) {
                for (int y = 0; y < HEIGHT; y++) {
                    System.arraycopy(values, (c * HEIGHT + y) * width, padded, (c * HEIGHT + y) * 320, width);
                }
            }
            values = padded;
            dimensions = new long[]{1, 3, HEIGHT, 320};
        }
        try (OnnxTensor input = OnnxTensor.createTensor(environment, FloatBuffer.wrap(values),
                    dimensions);
                OrtSession.Result result = session.run(Collections.singletonMap("x", input))) {
            OnnxTensor output = (OnnxTensor) result.get(0);
            long[] shape = output.getInfo().getShape();
            if (shape.length != 3 || shape[0] != 1 || shape[2] != CLASS_COUNT) {
                throw new IOException("手写识别模型与字符表不匹配");
            }
            FloatBuffer buffer = output.getFloatBuffer();
            float[] scores = new float[buffer.remaining()];
            buffer.get(scores);
            for (float score : scores) {
                if (!Float.isFinite(score)) throw new IOException("识别输出异常，请重试");
            }
            Pair<String, Float> decoded = CTCDecoder.INSTANCE.decode(scores, shape,
                    alphabet.subList(1, alphabet.size())).get(0);
            return decoded;
        }
    }

    private String recognize(Bitmap bitmap, Rect region, int contentWidth, boolean isolatedCell) throws OrtException {
        int width = Math.max(MIN_WIDTH, (contentWidth + 15) / 16 * 16);
        int plane = width * HEIGHT;
        float[] values = new float[3 * plane];
        int[] pixels = new int[contentWidth * HEIGHT];
        Bitmap resized = Bitmap.createBitmap(contentWidth, HEIGHT, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(resized);
            canvas.drawBitmap(bitmap, region, new Rect(0, 0, contentWidth, HEIGHT), filter);
            canvas.setBitmap(null);
            resized.getPixels(pixels, 0, contentWidth, 0, 0, contentWidth, HEIGHT);
        } finally {
            resized.recycle();
        }
        // The model takes normalized BGR planes. The padding to the right remains zero.
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < contentWidth; x++) {
                int color = pixels[y * contentWidth + x];
                int index = y * width + x;
                values[index] = (color & 255) / 127.5f - 1f;
                values[index + plane] = (color >>> 8 & 255) / 127.5f - 1f;
                values[index + 2 * plane] = (color >>> 16 & 255) / 127.5f - 1f;
            }
        }
        try (OnnxTensor input = OnnxTensor.createTensor(environment, FloatBuffer.wrap(values),
                    new long[]{1, 3, HEIGHT, width});
                OrtSession.Result result = session.run(Collections.singletonMap("x", input))) {
            OnnxTensor probabilities = (OnnxTensor) result.get(0);
            long[] shape = probabilities.getInfo().getShape();
            if (shape.length != 3 || shape[0] != 1 || shape[2] != CLASS_COUNT) {
                throw new IllegalStateException("Unexpected recognition output shape");
            }
            return decode(probabilities.getFloatBuffer(), (int) shape[1], isolatedCell);
        }
    }

    private String decode(FloatBuffer scores, int steps, boolean isolatedCell) {
        int[] labels = new int[steps];
        float[] confidence = new float[steps];
        int length = 0;
        int previous = 0;
        for (int step = 0; step < steps; step++) {
            int best = 0;
            float peak = scores.get();
            for (int i = 1; i < CLASS_COUNT; i++) {
                float score = scores.get();
                if (score > peak) {
                    peak = score;
                    best = i;
                }
            }
            if (best != 0) {
                if (best != previous) {
                    labels[length] = best;
                    confidence[length++] = peak;
                } else {
                    confidence[length - 1] = Math.max(confidence[length - 1], peak);
                }
            }
            previous = best;
        }
        StringBuilder text = new StringBuilder();
        float hanSum = 0f;
        int hanCount = 0;
        float allSum = 0f;
        for (int i = 0; i < length; i++) {
            String label = alphabet.get(labels[i]);
            if (isolatedCell && confidence[i] < .85f) return "";
            allSum += confidence[i];
            if (HAN_RUN.matcher(label).matches()) {
                if (confidence[i] < MIN_HAN_CONFIDENCE) return "";
                hanSum += confidence[i];
                hanCount++;
            }
            text.append(label);
        }
        if (isolatedCell) return length > 0 && allSum >= .94f * length ? text.toString().trim() : "";
        return hanCount > 0 && hanSum >= MEAN_HAN_CONFIDENCE * hanCount ? text.toString() : "";
    }

    // Require each non-Han span to agree and keep its original spelling and punctuation.
    static String mergeHan(String original, String candidate) {
        Matcher before = HAN_RUN.matcher(original);
        Matcher after = HAN_RUN.matcher(candidate);
        StringBuilder merged = new StringBuilder(original.length());
        int originalEnd = 0;
        int candidateEnd = 0;
        while (before.find()) {
            if (!after.find() || !sameAnchor(original.substring(originalEnd, before.start()),
                    candidate.substring(candidateEnd, after.start()))) return original;
            String run = before.group();
            String oldHan = SPACE.matcher(run).replaceAll("");
            String newHan = SPACE.matcher(after.group()).replaceAll("");
            if (!oldHan.equals(newHan)) {
                int[] oldCharacters = oldHan.codePoints().toArray();
                int[] newCharacters = newHan.codePoints().toArray();
                int maxEdits = (Math.max(oldCharacters.length, newCharacters.length) + 2) / 3;
                if (editDistance(oldCharacters, newCharacters) > maxEdits) return original;
                run = oldCharacters.length == newCharacters.length ? keepSpacing(run, newCharacters) : newHan;
            }
            merged.append(original, originalEnd, before.start()).append(run);
            originalEnd = before.end();
            candidateEnd = after.end();
        }
        if (after.find() || !sameAnchor(original.substring(originalEnd), candidate.substring(candidateEnd))) {
            return original;
        }
        return merged.append(original, originalEnd, original.length()).toString();
    }

    private static boolean sameAnchor(String original, String candidate) {
        return compactAnchor(original).equals(compactAnchor(candidate));
    }

    private static String compactAnchor(String text) {
        return SPACE.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC)).replaceAll("");
    }

    private static String keepSpacing(String original, int[] han) {
        StringBuilder result = new StringBuilder(original.length());
        int i = 0;
        for (int offset = 0; offset < original.length();) {
            int codePoint = original.codePointAt(offset);
            boolean space = Character.isSpaceChar(codePoint) || codePoint == '\t';
            result.appendCodePoint(space ? codePoint : han[i++]);
            offset += Character.charCount(codePoint);
        }
        return result.toString();
    }

    private static int editDistance(int[] before, int[] after) {
        int[] row = new int[after.length + 1];
        for (int c = 0; c <= after.length; c++) row[c] = c;
        for (int r = 1; r <= before.length; r++) {
            int diagonal = row[0];
            row[0] = r;
            for (int c = 1; c <= after.length; c++) {
                int previous = row[c];
                int substitution = before[r - 1] == after[c - 1] ? 0 : 1;
                row[c] = Math.min(diagonal + substitution, Math.min(row[c - 1], previous) + 1);
                diagonal = previous;
            }
        }
        return row[after.length];
    }

    @Override public void close() {
        try {
            if (tableStructure != null) tableStructure.close();
        } catch (OrtException failure) {
            Log.w(TAG, "Unable to close table structure session", failure);
        } finally {
            try {
                if (session != null) session.close();
            } catch (OrtException failure) {
                Log.w(TAG, "Unable to close recognition session", failure);
            }
        }
    }
}
