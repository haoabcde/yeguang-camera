package com.scandesk.app;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;

/** Conservative, on-device preview hints. A ready hint never blocks or triggers the shutter. */
final class CaptureGuidance {
    enum Issue {
        SEARCHING("让纸张四角留在画面内"),
        DARK("光线较暗，请移到明亮处"),
        GLARE("局部过亮，稍换角度避开反光"),
        EDGE("留出纸张四角，避免切掉边缘"),
        TOO_FAR("靠近一些，让文字更清楚"),
        BLUR("文字可能模糊，轻点文字对焦"),
        MOVING("拿稳手机，稍停一下"),
        READY("画面已稳定，可以拍摄");
        final String message;
        Issue(String message) { this.message = message; }
    }

    static final class Result {
        final Issue issue;
        final CropQuad paper;
        Result(Issue issue, CropQuad paper) { this.issue = issue; this.paper = paper; }
    }

    private byte[] previous;
    private CropQuad previousPaper;
    private long stableSince, previousTime;
    private int stableFrames;

    void reset() {
        previous = null;
        previousPaper = null;
        stableSince = previousTime = 0;
        stableFrames = 0;
    }

    Result analyze(Bitmap frame, long now) throws IOException {
        return evaluate(frame, ScanImages.detect(frame), now);
    }

    Result evaluate(Bitmap frame, CropQuad paper, long now) {
        int w = frame.getWidth(), h = frame.getHeight();
        int[] pixels = new int[w * h];
        frame.getPixels(pixels, 0, w, 0, 0, w, h);
        int[] histogram = new int[256];
        int samples = 0, details = 0;
        int[] gray = new int[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            gray[i] = (77 * Color.red(c) + 150 * Color.green(c) + 29 * Color.blue(c)) >> 8;
        }
        for (int y = 2; y < h - 2; y += 2) {
            for (int x = 2; x < w - 2; x += 2) {
                if (paper != null && !inside(paper, x / (float) w, y / (float) h, .025f)) continue;
                int p = y * w + x, light = gray[p];
                histogram[light]++;
                samples++;
                if (Math.abs(light - gray[p + 1]) >= 24 || Math.abs(light - gray[p + w]) >= 24) details++;
            }
        }
        int median = percentile(histogram, samples, .5f);
        Issue issue;
        if (samples > 0 && median < 68) issue = Issue.DARK;
        else if (paper == null) issue = Issue.SEARCHING;
        else if (touchesEdge(paper)) issue = Issue.EDGE;
        else if (area(paper) < .30f) issue = Issue.TOO_FAR;
        else if (hasHotspot(gray, w, h, paper)) issue = Issue.GLARE;
        else if (CaptureQuality.inspect(frame) == CaptureQuality.Issue.BLUR) issue = Issue.BLUR;
        else if (details < Math.max(18, samples / 600)) issue = Issue.SEARCHING;
        else issue = Issue.MOVING;

        byte[] thumbnail = thumbnail(gray, w, h);
        if (issue != Issue.MOVING) {
            stableFrames = 0;
            stableSince = 0;
        } else {
            boolean consecutive = previous != null && previousPaper != null && previousTime <= now
                    && now - previousTime < 1500;
            float displacement = consecutive ? displacement(previousPaper, paper) : 1;
            float motion = consecutive ? motion(previous, thumbnail) : 255;
            if (!consecutive || stableFrames == 0 || displacement > .018f || motion > 12f) {
                stableSince = now;
                stableFrames = 1;
            } else stableFrames++;
            if (stableFrames >= 3 && now - stableSince >= 650) issue = Issue.READY;
        }
        previous = thumbnail;
        previousPaper = paper;
        previousTime = now;
        return new Result(issue, paper);
    }

    /** Read the visible Y plane, including arbitrary row/pixel stride and nonzero buffer position. */
    static Bitmap lumaFrame(ByteBuffer source, int width, int height, int rowStride, int pixelStride,
            Rect requested, int maxEdge) {
        Rect crop = new Rect(requested);
        if (width <= 0 || height <= 0 || rowStride <= 0 || pixelStride <= 0
                || !crop.intersect(0, 0, width, height) || maxEdge < 1) {
            throw new IllegalArgumentException("无效的相机分析图像");
        }
        float scale = Math.min(1, maxEdge / (float) Math.max(crop.width(), crop.height()));
        int outWidth = Math.max(1, Math.round(crop.width() * scale));
        int outHeight = Math.max(1, Math.round(crop.height() * scale));
        int[] pixels = new int[outWidth * outHeight];
        ByteBuffer plane = source.duplicate();
        int start = plane.position(), end = plane.limit();
        for (int y = 0; y < outHeight; y++) {
            int sy = crop.top + Math.min(crop.height() - 1, (int) ((y + .5f) * crop.height() / outHeight));
            for (int x = 0; x < outWidth; x++) {
                int sx = crop.left + Math.min(crop.width() - 1, (int) ((x + .5f) * crop.width() / outWidth));
                int index = start + sy * rowStride + sx * pixelStride;
                if (index < start || index >= end) throw new IllegalArgumentException("相机亮度平面不完整");
                int value = plane.get(index) & 255;
                pixels[y * outWidth + x] = Color.rgb(value, value, value);
            }
        }
        return Bitmap.createBitmap(pixels, outWidth, outHeight, Bitmap.Config.ARGB_8888);
    }

    /** Detection is crop-relative; CameraX's transform accepts original buffer coordinates. */
    static float[] previewPoints(CropQuad paper, Rect crop, Matrix rawBufferToPreview) {
        if (paper == null) return null;
        float[] points = paper.values();
        for (int i = 0; i < points.length; i += 2) {
            points[i] = crop.left + points[i] * crop.width();
            points[i + 1] = crop.top + points[i + 1] * crop.height();
        }
        rawBufferToPreview.mapPoints(points);
        return points;
    }

    private static boolean touchesEdge(CropQuad paper) {
        for (float p : paper.values()) if (p < .025f || p > .975f) return true;
        return false;
    }

    private static float area(CropQuad paper) {
        float sum = 0;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            sum += paper.x(i) * paper.y(j) - paper.x(j) * paper.y(i);
        }
        return Math.abs(sum) / 2;
    }

    private static boolean inside(CropQuad paper, float x, float y, float margin) {
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            float dx = paper.x(j) - paper.x(i), dy = paper.y(j) - paper.y(i);
            if (dx * (y - paper.y(i)) - dy * (x - paper.x(i)) < margin * Math.hypot(dx, dy)) return false;
        }
        return true;
    }

    // A bright white sheet is not glare. Require a small, isolated clipped region whose
    // surrounding paper is substantially darker; broad white backgrounds are rejected.
    private static boolean hasHotspot(int[] gray, int width, int height, CropQuad paper) {
        final int cols = 32, rows = 24;
        int[] means = new int[cols * rows], counts = new int[cols * rows], hot = new int[cols * rows];
        int maximum = 0;
        for (int y = 1; y < height - 1; y += 2) for (int x = 1; x < width - 1; x += 2) {
            if (!inside(paper, x / (float) width, y / (float) height, .02f)) continue;
            maximum = Math.max(maximum, gray[y * width + x]);
        }
        if (maximum < 225) return false;
        int clipped = Math.max(225, maximum - 3);
        for (int y = 1; y < height - 1; y += 2) for (int x = 1; x < width - 1; x += 2) {
            if (!inside(paper, x / (float) width, y / (float) height, .02f)) continue;
            int index = Math.min(rows - 1, y * rows / height) * cols + Math.min(cols - 1, x * cols / width);
            int value = gray[y * width + x];
            means[index] += value;
            counts[index]++;
            if (value >= clipped) hot[index]++;
        }
        boolean[] mask = new boolean[means.length], visited = new boolean[means.length];
        int paperBlocks = 0;
        for (int i = 0; i < means.length; i++) if (counts[i] > 0) {
            means[i] /= counts[i];
            paperBlocks++;
            mask[i] = hot[i] > counts[i] * .68f;
        }
        for (int first = 0; first < mask.length; first++) {
            if (!mask[first] || visited[first]) continue;
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(first);
            visited[first] = true;
            int size = 0;
            boolean[] ring = new boolean[means.length];
            while (!queue.isEmpty()) {
                int index = queue.removeFirst();
                size++;
                int x = index % cols, y = index / cols;
                for (int k = 0; k < 4; k++) {
                    int nx = x + (k == 0 ? -1 : k == 1 ? 1 : 0);
                    int ny = y + (k == 2 ? -1 : k == 3 ? 1 : 0);
                    if (nx < 0 || nx >= cols || ny < 0 || ny >= rows) continue;
                    int next = ny * cols + nx;
                    if (mask[next]) {
                        if (!visited[next]) { visited[next] = true; queue.add(next); }
                    } else if (counts[next] > 0) ring[next] = true;
                }
            }
            if (size < 4 || size > paperBlocks * .20f) continue;
            int[] surroundings = new int[means.length];
            int n = 0;
            for (int i = 0; i < ring.length; i++) if (ring[i]) surroundings[n++] = means[i];
            if (n < 5) continue;
            Arrays.sort(surroundings, 0, n);
            if (surroundings[n / 2] <= clipped - 30) return true;
        }
        return false;
    }

    private static int percentile(int[] histogram, int count, float fraction) {
        int wanted = Math.max(1, (int) Math.ceil(count * fraction)), total = 0;
        for (int i = 0; i < histogram.length; i++) {
            total += histogram[i];
            if (total >= wanted) return i;
        }
        return 0;
    }

    private static byte[] thumbnail(int[] gray, int width, int height) {
        byte[] out = new byte[32 * 24];
        for (int y = 0; y < 24; y++) for (int x = 0; x < 32; x++) {
            int sx = Math.min(width - 1, (int) ((x + .5f) * width / 32));
            int sy = Math.min(height - 1, (int) ((y + .5f) * height / 24));
            out[y * 32 + x] = (byte) gray[sy * width + sx];
        }
        return out;
    }

    private static float motion(byte[] first, byte[] second) {
        float bias = 0;
        for (int i = 0; i < first.length; i++) bias += (second[i] & 255) - (first[i] & 255);
        bias /= first.length;
        float difference = 0;
        for (int i = 0; i < first.length; i++) difference += Math.abs((second[i] & 255) - (first[i] & 255) - bias);
        return difference / first.length;
    }

    private static float displacement(CropQuad first, CropQuad second) {
        float max = 0;
        for (int i = 0; i < 4; i++) max = Math.max(max,
                (float) Math.hypot(first.x(i) - second.x(i), first.y(i) - second.y(i)));
        return max;
    }
}
