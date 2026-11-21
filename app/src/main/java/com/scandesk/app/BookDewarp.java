package com.scandesk.app;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.PointF;
import android.os.SystemClock;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import com.paddle.ocr.model.OCRBox;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/** Offline UVDoc inference. Supply one upright, cropped page, before enhancement.
 *
 * The source is never changed or recycled. The caller owns Result.image and must
 * recycle it. A result is a geometric proposal, not proof that OCR improved; show
 * a comparison before applying. No user image is used to train this model.
 *
 * The checkpoint must be thread-safe: during ONNX inference a cancellation guard
 * also invokes it every 25 ms and calls RunOptions.setTerminate on cancellation.
 * This matches JobState.check's AtomicBoolean contract. Calls are serialized per
 * instance; close only after the worker operation has returned.
 */
final class BookDewarp implements AutoCloseable {
    static final int MAX_EDGE = 3000;
    static final int INPUT_WIDTH = 488, INPUT_HEIGHT = 712;
    static final int GRID_WIDTH = 31, GRID_HEIGHT = 45;
    private static final int STRIP_HEIGHT = 96;
    private static final Runnable NO_CHECKPOINT = () -> { };

    private final OrtEnvironment environment;
    private final AssetManager assets;
    private OrtSession session;
    private HandwritingOcr textDetector;

    BookDewarp(AssetManager assets) throws IOException {
        this.assets = assets;
        ScanImages.requireVision();
        environment = OrtEnvironment.getEnvironment();
        try {
            session = ChineseOcr.openSession(assets, environment, "vision/uvdoc.onnx");
        } catch (OrtException | LinkageError | OutOfMemoryError failure) {
            throw new IOException("书页展平模型暂时无法加载，请关闭其他文档后重试", failure);
        }
    }

    static final class Result {
        final Bitmap image;
        /** RMS nonlinear displacement / page diagonal; not model confidence. */
        final float nonlinearDisplacement;
        final float displacementPixels;
        final float outsideFraction;
        final float foldedFraction;
        final long elapsedMillis;

        Result(Bitmap image, GridStats stats, long elapsedMillis) {
            this.image = image;
            nonlinearDisplacement = stats.nonlinearDisplacement;
            displacementPixels = stats.displacementPixels;
            outsideFraction = stats.outsideFraction;
            foldedFraction = stats.foldedFraction;
            this.elapsedMillis = elapsedMillis;
        }

        boolean changed() { return displacementPixels >= 1f; }
    }

    synchronized Result flatten(Bitmap source, Runnable checkpoint) throws IOException {
        if (session == null) throw new IOException("书页展平模块已关闭");
        if (source == null || source.isRecycled()) throw new IOException("请重新选择书页图片");
        int width = source.getWidth(), height = source.getHeight();
        if (Math.min(width, height) < 64 || Math.max(width, height) > MAX_EDGE) {
            throw new IOException("请使用长边不超过 3000 像素、完整清晰的单页书页");
        }
        Runnable check = checkpoint == null ? NO_CHECKPOINT : checkpoint;
        long start = SystemClock.elapsedRealtime();
        try {
            checkCancellation(check);
            float[] input = prepareInput(source, check);
            float[] grid = predict(input, check);
            GridStats stats = validateGrid(grid, GRID_WIDTH, GRID_HEIGHT, width, height);
            checkContentPreservation(source, grid, check);
            checkDetectedTextPreservation(source, grid, check);
            Bitmap image = remap(source, grid, GRID_WIDTH, GRID_HEIGHT, check);
            return new Result(image, stats, SystemClock.elapsedRealtime() - start);
        } catch (OrtException failure) {
            checkCancellation(check);
            throw new IOException("书页展平推理失败，请重新裁切为单页后重试", failure);
        } catch (OutOfMemoryError failure) {
            throw new IOException("书页过大，内存不足，请缩小图片后重试", failure);
        }
    }

    /** Exact align-corners bilinear input sampling, with BGR /255 CHW channels.
     * Canvas/Bitmap scaling and cv::resize use different pixel-center conventions.
     */
    static float[] prepareInput(Bitmap source, Runnable check) {
        int width = source.getWidth(), height = source.getHeight();
        int plane = INPUT_WIDTH * INPUT_HEIGHT;
        float[] tensor = new float[3 * plane];
        int[] top = new int[width], bottom = new int[width];
        for (int y = 0; y < INPUT_HEIGHT; y++) {
            if ((y & 15) == 0) checkCancellation(check);
            float sy = y * (height - 1f) / (INPUT_HEIGHT - 1);
            int y0 = (int) sy, y1 = Math.min(y0 + 1, height - 1);
            float fy = sy - y0;
            source.getPixels(top, 0, width, 0, y0, width, 1);
            source.getPixels(bottom, 0, width, 0, y1, width, 1);
            for (int x = 0; x < INPUT_WIDTH; x++) {
                float sx = x * (width - 1f) / (INPUT_WIDTH - 1);
                int x0 = (int) sx, x1 = Math.min(x0 + 1, width - 1);
                float fx = sx - x0;
                for (int channel = 0; channel < 3; channel++) {
                    int shift = channel * 8; // Bitmap ARGB -> B, G, R.
                    float a = (top[x0] >> shift & 255) / 255f;
                    float b = (top[x1] >> shift & 255) / 255f;
                    float c = (bottom[x0] >> shift & 255) / 255f;
                    float d = (bottom[x1] >> shift & 255) / 255f;
                    tensor[channel * plane + y * INPUT_WIDTH + x] =
                            (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy;
                }
            }
        }
        return tensor;
    }

    private float[] predict(float[] values, Runnable check) throws OrtException, IOException {
        checkCancellation(check);
        try (OnnxTensor input = OnnxTensor.createTensor(environment, FloatBuffer.wrap(values),
                    new long[]{1, 3, INPUT_HEIGHT, INPUT_WIDTH});
                OrtSession.RunOptions options = new OrtSession.RunOptions();
                InferenceCancellation guard = new InferenceCancellation(options, check)) {
            try (OrtSession.Result output = session.run(Collections.singletonMap("image", input), options)) {
                guard.rethrow();
                checkCancellation(check);
                OnnxTensor tensor = (OnnxTensor) output.get(0);
                long[] shape = tensor.getInfo().getShape();
                if (shape.length != 4 || shape[0] != 1 || shape[1] != 2
                        || shape[2] != GRID_HEIGHT || shape[3] != GRID_WIDTH) {
                    throw new IOException("书页展平模型网格不匹配");
                }
                FloatBuffer buffer = tensor.getFloatBuffer();
                float[] grid = new float[2 * GRID_WIDTH * GRID_HEIGHT];
                buffer.get(grid);
                return grid;
            } catch (OrtException failure) {
                guard.rethrow();
                throw failure;
            }
        }
    }

    static final class GridStats {
        final float nonlinearDisplacement, displacementPixels, outsideFraction, foldedFraction;
        GridStats(float nonlinear, float displacement, float outside, float folded) {
            nonlinearDisplacement = nonlinear;
            displacementPixels = displacement;
            outsideFraction = outside;
            foldedFraction = folded;
        }
    }

    /** Rejects unusable geometry; these checks cannot detect every semantic error. */
    static GridStats validateGrid(float[] grid, int cols, int rows, int width, int height)
            throws IOException {
        if (cols < 2 || rows < 2 || grid == null || grid.length != 2 * cols * rows) {
            throw new IOException("展平网格尺寸无效");
        }
        int plane = cols * rows, outside = 0, folded = 0;
        double residual = 0, displacement = 0, area = 0;
        for (int i = 0; i < plane; i++) {
            float x = grid[i], y = grid[plane + i];
            if (!Float.isFinite(x) || !Float.isFinite(y) || Math.abs(x) > 1.3f || Math.abs(y) > 1.3f) {
                throw new IOException("未得到可靠的书页曲面，请调整裁切或重新拍摄");
            }
            if (Math.abs(x) > 1f || Math.abs(y) > 1f) outside++;
            float u = (i % cols) / (cols - 1f), v = (i / cols) / (rows - 1f);
            float bx = interpolateCorners(grid, 0, cols, rows, u, v);
            float by = interpolateCorners(grid, plane, cols, rows, u, v);
            double dx = (x - bx) * (width - 1) * .5, dy = (y - by) * (height - 1) * .5;
            residual += dx * dx + dy * dy;
            dx = (x - (2 * u - 1)) * (width - 1) * .5;
            dy = (y - (2 * v - 1)) * (height - 1) * .5;
            displacement += dx * dx + dy * dy;
        }
        for (int y = 0; y < rows - 1; y++) {
            for (int x = 0; x < cols - 1; x++) {
                int tl = y * cols + x, tr = tl + 1, bl = tl + cols, br = bl + 1;
                double a = cross(grid, plane, tl, tr, tl, bl);
                double b = cross(grid, plane, tl, tr, tr, br);
                double c = cross(grid, plane, bl, br, tl, bl);
                double d = cross(grid, plane, bl, br, tr, br);
                if (Math.min(Math.min(a, b), Math.min(c, d)) <= 1e-7) folded++;
                area += (a + d) * .5;
            }
        }
        float outsideFraction = outside / (float) plane;
        float foldedFraction = folded / (float) ((cols - 1) * (rows - 1));
        if (folded > 0 || outsideFraction > .08f || area < .6 || area > 5.2) {
            throw new IOException("书页曲面不够可靠，请保留原稿并重新裁切单页");
        }
        return new GridStats((float) (Math.sqrt(residual / plane) / Math.hypot(width, height)),
                (float) Math.sqrt(displacement / plane), outsideFraction, foldedFraction);
    }

    private static double cross(float[] grid, int n, int a, int b, int c, int d) {
        return (double) (grid[b] - grid[a]) * (grid[n + d] - grid[n + c])
                - (double) (grid[n + b] - grid[n + a]) * (grid[d] - grid[c]);
    }

    private static float interpolateCorners(float[] grid, int offset, int cols, int rows, float u, float v) {
        return (grid[offset] * (1 - u) + grid[offset + cols - 1] * u) * (1 - v)
                + (grid[offset + (rows - 1) * cols] * (1 - u) + grid[offset + rows * cols - 1] * u) * v;
    }

    /** Use the existing offline text detector to protect short edge headings and
     * page numbers as well as body lines. Geometry-only components cannot reliably
     * distinguish a three-digit folio from a shadow or photographed desk texture.
     * No text recognition or recognition-model loading is needed here.
     */
    void checkDetectedTextPreservation(Bitmap source, float[] grid, Runnable check)
            throws IOException, OrtException {
        checkCancellation(check);
        float scale = Math.min(1, 1200f / Math.max(source.getWidth(), source.getHeight()));
        Bitmap probe = Bitmap.createScaledBitmap(source, Math.max(1, Math.round(source.getWidth() * scale)),
                Math.max(1, Math.round(source.getHeight() * scale)), true);
        try {
            if (textDetector == null) textDetector = new HandwritingOcr(assets);
            List<OCRBox> boxes;
            try (OrtSession.RunOptions options = new OrtSession.RunOptions();
                    InferenceCancellation guard = new InferenceCancellation(options, check)) {
                try {
                    boxes = textDetector.detectText(probe, check, options);
                    guard.rethrow();
                } catch (OrtException failure) {
                    guard.rethrow();
                    throw failure;
                }
            }
            protectTextRegions(probe, grid, boxes, check);
        } finally {
            if (probe != source) probe.recycle();
        }
    }

    static void protectTextRegions(Bitmap source, float[] grid, List<OCRBox> boxes, Runnable check)
            throws IOException {
        int width = source.getWidth(), height = source.getHeight();
        Mat rgba = new Mat(), gray = new Mat(), local = new Mat(), ink = new Mat();
        Mat outside = Mat.zeros(height, width, CvType.CV_8UC1);
        Mat region = Mat.zeros(height, width, CvType.CV_8UC1), strokes = new Mat(), lost = new Mat();
        MatOfPoint boundary = new MatOfPoint(), text = new MatOfPoint();
        try {
            Utils.bitmapToMat(source, rgba);
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);
            rgba.release();
            Imgproc.GaussianBlur(gray, local, new Size(0, 0), 5);
            Core.subtract(local, gray, ink);
            Imgproc.threshold(ink, ink, 14, 255, Imgproc.THRESH_BINARY);
            boundary.fromArray(gridOutline(grid, width, height));
            Imgproc.fillPoly(outside, Collections.singletonList(boundary), new Scalar(255));
            Core.bitwise_not(outside, outside);
            for (OCRBox box : boxes) {
                checkCancellation(check);
                List<PointF> corners = box.getPoints();
                if (corners.size() != 4) throw new IOException("文字保护区域无效，请重试");
                Point[] points = new Point[4];
                for (int i = 0; i < 4; i++) {
                    PointF point = corners.get(i);
                    if (!Float.isFinite(point.x) || !Float.isFinite(point.y)) {
                        throw new IOException("文字保护坐标无效，请重试");
                    }
                    points[i] = new Point(Math.round(point.x), Math.round(point.y));
                }
                text.fromArray(points);
                region.setTo(new Scalar(0));
                Imgproc.fillPoly(region, Collections.singletonList(text), new Scalar(255));
                Core.bitwise_and(ink, region, strokes);
                Core.bitwise_and(strokes, outside, lost);
                int totalInk = Core.countNonZero(strokes), lostInk = Core.countNonZero(lost);
                // No outer-border exemption: a tiny heading or folio is content.
                // Count actual ink, not detector padding outside the page.
                if (totalInk >= 8 && lostInk >= Math.max(2, totalInk * .01)) {
                    throw new IOException("展平可能裁掉页眉、页脚或文字，已保留原稿，请扩大裁切范围后重试");
                }
            }
        } finally {
            text.release();
            boundary.release();
            lost.release();
            strokes.release();
            region.release();
            outside.release();
            ink.release();
            local.release();
            gray.release();
            rgba.release();
        }
    }

    private static Point[] gridOutline(float[] grid, int width, int height) {
        Point[] outline = new Point[2 * (GRID_WIDTH + GRID_HEIGHT) - 4];
        int index = 0;
        for (int x = 0; x < GRID_WIDTH; x++) outline[index++] = gridPoint(grid, x, 0, width, height);
        for (int y = 1; y < GRID_HEIGHT; y++) outline[index++] = gridPoint(grid, GRID_WIDTH - 1, y, width, height);
        for (int x = GRID_WIDTH - 2; x >= 0; x--) outline[index++] = gridPoint(grid, x, GRID_HEIGHT - 1, width, height);
        for (int y = GRID_HEIGHT - 2; y > 0; y--) outline[index++] = gridPoint(grid, 0, y, width, height);
        return outline;
    }

    /** A model can produce an unfolded, finite grid that still cuts valid content.
     * Protect long ruled-table lines and reliable horizontal text groups before
     * accepting it. Background texture and short isolated strokes are not treated
     * as text. This is a conservative loss check, not a universal quality score.
     */
    static void checkContentPreservation(Bitmap source, float[] grid, Runnable check) throws IOException {
        checkCancellation(check);
        int originalWidth = source.getWidth(), originalHeight = source.getHeight();
        double scale = Math.min(1, 1200.0 / Math.max(originalWidth, originalHeight));
        int width = Math.max(2, (int) Math.round(originalWidth * scale));
        int height = Math.max(2, (int) Math.round(originalHeight * scale));
        Mat rgba = new Mat(), resized = new Mat(), gray = new Mat(), local = new Mat();
        Mat ink = new Mat(), mask = Mat.zeros(height, width, CvType.CV_8UC1);
        Mat horizontal = new Mat(), vertical = new Mat(), rules = new Mat(), lost = new Mat();
        Mat closed = new Mat(), labels = new Mat(), stats = new Mat(), centroids = new Mat();
        Mat hKernel = null, vKernel = null, textKernel = null;
        MatOfPoint boundary = new MatOfPoint();
        try {
            Utils.bitmapToMat(source, rgba);
            Imgproc.resize(rgba, resized, new Size(width, height), 0, 0, Imgproc.INTER_AREA);
            rgba.release();
            Imgproc.cvtColor(resized, gray, Imgproc.COLOR_RGBA2GRAY);
            resized.release();
            Imgproc.GaussianBlur(gray, local, new Size(0, 0), 5);
            Core.subtract(local, gray, ink);
            Imgproc.threshold(ink, ink, 14, 255, Imgproc.THRESH_BINARY);
            boundary.fromArray(gridOutline(grid, width, height));
            Imgproc.fillPoly(mask, Collections.singletonList(boundary), new Scalar(255));
            Core.bitwise_not(mask, mask); // Pixels outside the predicted source footprint.
            hKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(Math.max(12, Math.round(width * .30)), 1));
            vKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(1, Math.max(12, Math.round(height * .25))));
            Imgproc.morphologyEx(ink, horizontal, Imgproc.MORPH_OPEN, hKernel);
            Imgproc.morphologyEx(ink, vertical, Imgproc.MORPH_OPEN, vKernel);
            // Both orientations are required: a book spine or desk edge alone is
            // not a protected ruled table. No flat-page auto-detection is claimed.
            if (Core.countNonZero(horizontal) >= 3 * width && Core.countNonZero(vertical) >= 2 * height) {
                Core.bitwise_or(horizontal, vertical, rules);
                Core.bitwise_and(rules, mask, lost);
                if (Core.countNonZero(lost) >= Math.max(12, Core.countNonZero(rules) * .002)) {
                    throw new IOException("展平可能裁掉表格边线，已保留原稿；平整表格请直接识别，书页请重新裁切");
                }
            }
            checkCancellation(check);
            textKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(Math.max(3, width / 40), 1));
            Imgproc.morphologyEx(ink, closed, Imgproc.MORPH_CLOSE, textKernel);
            int count = Imgproc.connectedComponentsWithStats(closed, labels, stats, centroids);
            int[] regions = new int[count * 5], components = new int[width * height];
            byte[] strokes = new byte[width * height], outside = new byte[width * height];
            stats.get(0, 0, regions);
            labels.get(0, 0, components);
            ink.get(0, 0, strokes);
            mask.get(0, 0, outside);
            for (int label = 1; label < count; label++) {
                int offset = 5 * label, x = regions[offset], y = regions[offset + 1];
                int w = regions[offset + 2], h = regions[offset + 3];
                if (y < height * .03 || y + h > height * .97 || h < Math.max(3, height * .007)
                        || h > height * .09 || w < width * .13 || w < h * 2.5) continue;
                int totalInk = 0, lostInk = 0;
                for (int row = y; row < y + h; row++) for (int column = x; column < x + w; column++) {
                    int pixel = row * width + column;
                    if (components[pixel] != label || strokes[pixel] == 0) continue;
                    totalInk++;
                    if (outside[pixel] != 0) lostInk++;
                }
                double density = totalInk / (double) (w * h);
                if (density >= .05 && density <= .7 && lostInk >= Math.max(6, totalInk * .01)) {
                    throw new IOException("展平可能裁掉文字，已保留原稿，请扩大裁切范围后重试");
                }
            }
            checkCancellation(check);
        } finally {
            boundary.release();
            if (textKernel != null) textKernel.release();
            if (vKernel != null) vKernel.release();
            if (hKernel != null) hKernel.release();
            centroids.release();
            stats.release();
            labels.release();
            closed.release();
            lost.release();
            rules.release();
            vertical.release();
            horizontal.release();
            mask.release();
            ink.release();
            local.release();
            gray.release();
            resized.release();
            rgba.release();
        }
    }

    private static Point gridPoint(float[] grid, int x, int y, int width, int height) {
        int i = y * GRID_WIDTH + x;
        return new Point(Math.round((grid[i] + 1) * .5f * (width - 1)),
                Math.round((grid[GRID_WIDTH * GRID_HEIGHT + i] + 1) * .5f * (height - 1)));
    }

    /** Grid coordinates are output -> source, normalized [-1,1], align_corners=True.
     * Only 96 output rows of maps/pixels exist at once, instead of full-resolution
     * float image tensors and two full-resolution displacement maps.
     */
    static Bitmap remap(Bitmap source, float[] grid, int cols, int rows, Runnable check) throws IOException {
        validateGrid(grid, cols, rows, source.getWidth(), source.getHeight());
        checkCancellation(check);
        int width = source.getWidth(), height = source.getHeight();
        Mat rgba = new Mat();
        Bitmap result = null;
        try {
            Utils.bitmapToMat(source, rgba);
            result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            for (int start = 0; start < height; start += STRIP_HEIGHT) {
                checkCancellation(check);
                int count = Math.min(STRIP_HEIGHT, height - start);
                float[] mx = new float[width * count];
                float[] my = new float[mx.length];
                for (int y = 0; y < count; y++) {
                    float gy = (start + y) * (rows - 1f) / (height - 1);
                    int y0 = Math.min((int) gy, rows - 2);
                    float fy = gy - y0;
                    for (int x = 0; x < width; x++) {
                        float gx = x * (cols - 1f) / (width - 1);
                        int x0 = Math.min((int) gx, cols - 2);
                        float fx = gx - x0;
                        mx[y * width + x] = (sampleGrid(grid, 0, cols, x0, y0, fx, fy) + 1) * .5f * (width - 1);
                        my[y * width + x] = (sampleGrid(grid, cols * rows, cols, x0, y0, fx, fy) + 1) * .5f * (height - 1);
                    }
                }
                Mat mapX = new Mat(count, width, CvType.CV_32FC1);
                Mat mapY = new Mat(count, width, CvType.CV_32FC1);
                Mat strip = new Mat();
                Bitmap pixels = null;
                try {
                    mapX.put(0, 0, mx);
                    mapY.put(0, 0, my);
                    // White outside the photograph is deliberate document padding;
                    // unlike model GridSample's black it creates no black page rim.
                    Imgproc.remap(rgba, strip, mapX, mapY, Imgproc.INTER_LINEAR,
                            Core.BORDER_CONSTANT, new Scalar(255, 255, 255, 255));
                    pixels = Bitmap.createBitmap(width, count, Bitmap.Config.ARGB_8888);
                    Utils.matToBitmap(strip, pixels);
                    int[] rowPixels = new int[width * count];
                    pixels.getPixels(rowPixels, 0, width, 0, 0, width, count);
                    result.setPixels(rowPixels, 0, width, 0, start, width, count);
                } finally {
                    if (pixels != null) pixels.recycle();
                    strip.release();
                    mapX.release();
                    mapY.release();
                }
            }
            checkCancellation(check);
            Bitmap complete = result;
            result = null;
            return complete;
        } finally {
            rgba.release();
            if (result != null) result.recycle();
        }
    }

    private static float sampleGrid(float[] grid, int offset, int cols, int x, int y, float fx, float fy) {
        int i = offset + y * cols + x;
        return (grid[i] * (1 - fx) + grid[i + 1] * fx) * (1 - fy)
                + (grid[i + cols] * (1 - fx) + grid[i + cols + 1] * fx) * fy;
    }

    private static void checkCancellation(Runnable check) {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException();
        check.run();
    }

    /** Terminates the running native graph, rather than waiting for a whole page. */
    private static final class InferenceCancellation implements AutoCloseable {
        final AtomicBoolean done = new AtomicBoolean();
        final AtomicReference<RuntimeException> cancellation = new AtomicReference<>();
        final Thread monitor;

        InferenceCancellation(OrtSession.RunOptions options, Runnable check) {
            monitor = new Thread(() -> {
                while (!done.get()) {
                    try {
                        check.run();
                    } catch (RuntimeException failure) {
                        cancellation.compareAndSet(null, failure);
                        try { options.setTerminate(true); } catch (OrtException ignored) { }
                        return;
                    }
                    LockSupport.parkNanos(25_000_000L);
                }
            }, "BookDewarp-cancellation");
            monitor.setDaemon(true);
            monitor.start();
        }

        void rethrow() {
            RuntimeException failure = cancellation.get();
            if (failure != null) throw failure;
        }

        @Override public void close() {
            done.set(true);
            monitor.interrupt();
            boolean interrupted = false;
            while (monitor.isAlive()) {
                try { monitor.join(); } catch (InterruptedException ignored) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    @Override public synchronized void close() {
        if (session != null) try { session.close(); } catch (OrtException ignored) { }
        session = null;
        if (textDetector != null) {
            try { textDetector.close(); } catch (IOException ignored) { }
            textDetector = null;
        }
        // OrtEnvironment is the process singleton also used by the OCR engines.
    }
}
