package com.scandesk.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.Size;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

final class ScanImages {
    static final int EXPORT_EDGE = 2800;
    static final int OCR_EDGE = 2300;
    private static boolean visionLoaded;

    private ScanImages() {}

    // Choose the initial look only when importing into the handwriting tool. Dark or
    // mixed backgrounds retain their ink colors; existing edited pages keep the user's filter.
    static ScanDocument.Filter initialHandwritingFilter(Bitmap thumbnail, CropQuad paper) {
        Matrix map = new Matrix();
        if (!map.setPolyToPoly(new float[]{0, 0, 1, 0, 1, 1, 0, 1}, 0,
                paper.pixels(thumbnail.getWidth() - 1, thumbnail.getHeight() - 1), 0, 4)) {
            return ScanDocument.Filter.ORIGINAL;
        }
        float[] points = new float[7 * 7 * 2];
        int n = 0;
        for (int y = 1; y <= 7; y++) {
            for (int x = 1; x <= 7; x++) {
                points[n++] = x / 8f;
                points[n++] = y / 8f;
            }
        }
        map.mapPoints(points);
        int light = 0;
        for (int i = 0; i < points.length; i += 2) {
            int color = thumbnail.getPixel(Math.round(points[i]), Math.round(points[i + 1]));
            if (Color.luminance(color) >= .50f) light++;
        }
        return light * 3 >= 2 * (points.length / 2) ? ScanDocument.Filter.AUTO : ScanDocument.Filter.ORIGINAL;
    }

    static synchronized void requireVision() throws IOException {
        if (!visionLoaded) {
            visionLoaded = OpenCVLoader.initLocal();
            if (visionLoaded) Core.setNumThreads(2);
        }
        if (!visionLoaded) throw new IOException("此设备无法加载图像处理组件");
    }

    static Bitmap source(File file, int quarterTurns, int maximumEdge) throws IOException {
        if (!file.isFile()) throw new IOException("找不到原始图片");
        Bitmap input = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file),
                (decoder, info, source) -> {
                    Size dimensions = info.getSize();
                    int width = dimensions.getWidth();
                    int height = dimensions.getHeight();
                    int edge = Math.max(width, height);
                    validateDimensions(width, height);
                    float scale = Math.min(1f, maximumEdge / (float) edge);
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    decoder.setTargetSize(Math.max(1, Math.round(width * scale)),
                            Math.max(1, Math.round(height * scale)));
                });
        if (quarterTurns == 0) return input;
        Matrix orientation = new Matrix();
        orientation.setRotate(90 * quarterTurns);
        Bitmap rotated = Bitmap.createBitmap(input, 0, 0, input.getWidth(),
                input.getHeight(), orientation, true);
        if (rotated != input) input.recycle();
        return rotated;
    }

    private static void validateDimensions(int width, int height) {
        if (Math.max(width, height) > 32768 || (long) width * height > 160_000_000
                || Math.min(width, height) < 12) {
            throw new IllegalArgumentException("图片尺寸超出可处理范围");
        }
    }

    static Bitmap renderWord(File file, ScanDocument.Page page) throws IOException {
        if (page.crop.equals(CropQuad.FULL)) return render(file, page, OCR_EDGE);

        // ImageDecoder exposes coordinates after EXIF normalization. Undo only the user's
        // quarter-turns to select the source region, then restore the chosen reading direction.
        CropQuad upright = page.crop;
        int undoTurns = (4 - page.rotation) % 4;
        for (int i = 0; i < undoTurns; i++) upright = upright.clockwise();
        final float[] corners = upright.values();
        final CropQuad[] localCrop = new CropQuad[1];
        Bitmap region = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file), (decoder, info, source) -> {
            int width = info.getSize().getWidth(), height = info.getSize().getHeight();
            validateDimensions(width, height);
            RectF bounds = new RectF(corners[0], corners[1], corners[0], corners[1]);
            for (int i = 2; i < corners.length; i += 2) {
                bounds.union(corners[i], corners[i + 1]);
            }
            double selectedEdge = Math.max(bounds.width() * width, bounds.height() * height);
            // Bound temporary decoding even on codecs which internally decode the whole image.
            double scale = Math.min(1, Math.min(OCR_EDGE / selectedEdge,
                    Math.min(4600.0 / Math.max(width, height), Math.sqrt(12_000_000.0 / ((long) width * height)))));
            int w = Math.max(1, (int) Math.round(width * scale));
            int h = Math.max(1, (int) Math.round(height * scale));
            android.graphics.Rect window = new android.graphics.Rect((int) Math.floor(bounds.left * w),
                    (int) Math.floor(bounds.top * h), (int) Math.ceil(bounds.right * w),
                    (int) Math.ceil(bounds.bottom * h));
            float[] local = new float[corners.length];
            for (int i = 0; i < corners.length; i += 2) {
                local[i] = (corners[i] * w - window.left) / window.width();
                local[i + 1] = (corners[i + 1] * h - window.top) / window.height();
            }
            localCrop[0] = CropQuad.from(local);
            if (localCrop[0] == null) throw new IllegalArgumentException("文字识别裁切区域无效");
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            decoder.setTargetSize(w, h);
            decoder.setCrop(window);
        });
        Bitmap corrected;
        try {
            corrected = rectify(region, localCrop[0]);
        } finally {
            region.recycle();
        }
        try {
            if (page.rotation != 0) {
                Matrix turn = new Matrix();
                turn.setRotate(90 * page.rotation);
                Bitmap rotated = Bitmap.createBitmap(corrected, 0, 0, corrected.getWidth(),
                        corrected.getHeight(), turn, true);
                if (rotated != corrected) corrected.recycle();
                corrected = rotated;
            }
            if (page.filter != ScanDocument.Filter.ORIGINAL) enhance(corrected, page.filter);
            return corrected;
        } catch (IOException | RuntimeException failure) {
            corrected.recycle();
            throw failure;
        }
    }

    static Bitmap render(File file, ScanDocument.Page page, int maximumEdge) throws IOException {
        Bitmap input = source(file, page.rotation, maximumEdge);
        if (page.crop.equals(CropQuad.FULL) && page.filter == ScanDocument.Filter.ORIGINAL
                && !input.hasAlpha()) return input;
        Bitmap corrected = null;
        try {
            corrected = rectify(input, page.crop);
            if (page.filter != ScanDocument.Filter.ORIGINAL) enhance(corrected, page.filter);
            return corrected;
        } catch (IOException | RuntimeException error) {
            if (corrected != null) corrected.recycle();
            throw error;
        } finally {
            input.recycle();
        }
    }

    static Bitmap rectify(Bitmap source, CropQuad crop) throws IOException {
        return project(source, crop, crop);
    }

    static Bitmap renderTable(File file, ScanDocument.Page page) throws IOException {
        Bitmap source = source(file, page.rotation, OCR_EDGE);
        Bitmap result = null;
        try {
            int longest = Math.max(source.getWidth(), source.getHeight());
            float scale = Math.min(1, 1100f / longest);
            Bitmap probe = Bitmap.createScaledBitmap(source,
                    Math.max(1, Math.round(source.getWidth() * scale)),
                    Math.max(1, Math.round(source.getHeight() * scale)), true);
            CropQuad paper;
            try {
                paper = detect(probe);
            } finally {
                if (probe != source) probe.recycle();
            }
            // Retain the user's selected area while aligning OCR with the paper plane.
            result = project(source, page.crop, paper == null ? page.crop : paper);
            if (page.filter != ScanDocument.Filter.ORIGINAL) enhance(result, page.filter);
            return result;
        } catch (IOException | RuntimeException failure) {
            if (result != null) result.recycle();
            throw failure;
        } finally {
            source.recycle();
        }
    }

    private static Bitmap project(Bitmap source, CropQuad selection, CropQuad axes) throws IOException {
        float[] corners = axes.pixels(source.getWidth(), source.getHeight());
        int planeWidth = Math.max(1, (int) Math.round((distance(corners, 0, 1)
                + distance(corners, 2, 3)) / 2));
        int planeHeight = Math.max(1, (int) Math.round((distance(corners, 0, 3)
                + distance(corners, 1, 2)) / 2));
        float[] destination = {0, 0, planeWidth, 0, planeWidth, planeHeight, 0, planeHeight};
        Matrix transform = new Matrix();
        if (!transform.setPolyToPoly(corners, 0, destination, 0, 4)) {
            throw new IOException("裁切区域无效，请调整四个角");
        }
        float[] mask = selection.pixels(source.getWidth(), source.getHeight());
        transform.mapPoints(mask);
        RectF bounds = new RectF(mask[0], mask[1], mask[0], mask[1]);
        for (int i = 0; i < mask.length; i += 2) {
            bounds.left = Math.min(bounds.left, mask[i]);
            bounds.top = Math.min(bounds.top, mask[i + 1]);
            bounds.right = Math.max(bounds.right, mask[i]);
            bounds.bottom = Math.max(bounds.bottom, mask[i + 1]);
        }
        int longest = Math.max(source.getWidth(), source.getHeight());
        if (!Float.isFinite(bounds.width()) || !Float.isFinite(bounds.height())
                || bounds.width() > longest * 2.2 || bounds.height() > longest * 2.2) {
            if (!axes.equals(selection)) return project(source, selection, selection);
            throw new IOException("裁切区域无效，请调整四个角");
        }
        float scale = Math.min(1, longest / Math.max(bounds.width(), bounds.height()));
        transform.postTranslate(-bounds.left, -bounds.top);
        transform.postScale(scale, scale);
        Path selected = new Path();
        selected.moveTo((mask[0] - bounds.left) * scale, (mask[1] - bounds.top) * scale);
        for (int i = 2; i < mask.length; i += 2) {
            selected.lineTo((mask[i] - bounds.left) * scale, (mask[i + 1] - bounds.top) * scale);
        }
        selected.close();
        int width = Math.max(1, Math.round(bounds.width() * scale));
        int height = Math.max(1, Math.round(bounds.height() * scale));
        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        try {
            canvas.drawColor(Color.WHITE);
            canvas.clipPath(selected);
            canvas.drawBitmap(source, transform, new Paint(Paint.FILTER_BITMAP_FLAG));
        } finally {
            canvas.setBitmap(null);
        }
        return result;
    }

    private static double distance(float[] corners, int a, int b) {
        return Math.hypot(corners[a * 2] - corners[b * 2],
                corners[a * 2 + 1] - corners[b * 2 + 1]);
    }

    static CropQuad detect(Bitmap source) throws IOException {
        requireVision();
        Mat rgba = new Mat();
        Mat gray = new Mat();
        Mat smooth = new Mat();
        Mat mask = new Mat();
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT,
                new org.opencv.core.Size(5, 5));
        List<Candidate> candidates = new ArrayList<>();
        try {
            Utils.bitmapToMat(source, rgba);
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);
            Imgproc.GaussianBlur(gray, smooth, new org.opencv.core.Size(5, 5), 0);
            byte[] luminance = new byte[gray.rows() * gray.cols()];
            gray.get(0, 0, luminance);
            rgba.release();
            gray.release();

            Imgproc.Canny(smooth, mask, 38, 110);
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel);
            candidates(mask, luminance, candidates);

            Imgproc.threshold(smooth, mask, 0, 255, Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU);
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel);
            candidates(mask, luminance, candidates);
            Candidate best = null;
            for (Candidate candidate : candidates) {
                if (best == null || candidate.score > best.score) best = candidate;
            }
            if (best != null) {
                // An outer sheet that cannot be represented safely is a reason to
                // retain the full photograph, not to select a nested table/panel.
                for (Candidate candidate : candidates) {
                    if (!candidate.preservesContour && candidate.area >= best.area
                            && contains(candidate.original, best.original)) return null;
                }
                if (!best.preservesContour) return null;
            }
            if (best == null || hasPrintedMargin(best.crop, luminance, smooth.cols(), smooth.rows())) return null;
            return best.crop;
        } finally {
            release(rgba, gray, smooth, mask, kernel);
        }
    }

    private static void candidates(Mat mask, byte[] luminance, List<Candidate> candidates) {
        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        MatOfPoint2f boundary = new MatOfPoint2f();
        MatOfPoint2f polygon = new MatOfPoint2f();
        MatOfPoint hull = new MatOfPoint();
        int width = mask.cols();
        int height = mask.rows();
        double total = (double) width * height;
        try {
            Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_LIST,
                    Imgproc.CHAIN_APPROX_SIMPLE);
            for (MatOfPoint contour : contours) {
                double area = Math.abs(Imgproc.contourArea(contour)) / total;
                if (area < 0.17 || area > 0.98) continue;
                Rect bounds = Imgproc.boundingRect(contour);
                int padding = Math.max(2, Math.min(width, height) / 180);
                if (bounds.x < padding && bounds.y < padding
                        && bounds.br().x > width - padding && bounds.br().y > height - padding) {
                    continue;
                }
                boundary.fromArray(contour.toArray());
                double length = Imgproc.arcLength(boundary, true);
                for (double tolerance : new double[]{0.017, 0.024, 0.035}) {
                    Imgproc.approxPolyDP(boundary, polygon, length * tolerance, true);
                    if (polygon.total() != 4) continue;
                    Point[] points = polygon.toArray();
                    hull.fromArray(points);
                    if (!Imgproc.isContourConvex(hull)) break;
                    double polygonArea = Math.abs(Imgproc.contourArea(hull)) / total;
                    if (polygonArea < 0.17 || Math.abs(area - polygonArea) > 0.09) break;
                    CropQuad crop = ordered(points, width, height);
                    if (crop == null) break;
                    double separation = edgeContrast(crop, luminance, width, height);
                    if (separation < 6) break;
                    double score = polygonArea + Math.min(0.26, separation / 150);
                    CropQuad containing = containContour(crop, contour.toArray(), width, height);
                    candidates.add(new Candidate(containing == null ? crop : containing, crop,
                            area, score, containing != null));
                    break;
                }
            }
        } finally {
            for (MatOfPoint contour : contours) contour.release();
            release(hierarchy, boundary, polygon, hull);
        }
    }

    /** A four-corner chord can slice off a curved sheet's outward-bowed margin.
     * Enclose its actual contour in the candidate's page plane before cropping.
     * If that enclosure leaves the photograph, abstain instead of clamping corners
     * inward and silently losing the protruding paper/header again.
     */
    private static CropQuad containContour(CropQuad crop, Point[] contour, int width, int height) {
        Matrix plane = new Matrix();
        if (!plane.setPolyToPoly(crop.pixels(width, height), 0,
                new float[]{0, 0, 1, 0, 1, 1, 0, 1}, 0, 4)) return null;
        float[] outline = new float[contour.length * 2];
        for (int i = 0; i < contour.length; i++) {
            outline[2 * i] = (float) contour[i].x;
            outline[2 * i + 1] = (float) contour[i].y;
        }
        plane.mapPoints(outline);
        float left = 0, top = 0, right = 1, bottom = 1;
        for (int i = 0; i < outline.length; i += 2) {
            if (!Float.isFinite(outline[i]) || !Float.isFinite(outline[i + 1])) return null;
            left = Math.min(left, outline[i]);
            right = Math.max(right, outline[i]);
            top = Math.min(top, outline[i + 1]);
            bottom = Math.max(bottom, outline[i + 1]);
        }
        // Ignore one-pixel contour/antialias rounding around a genuinely straight
        // sheet, preserving the existing precise four-corner behavior.
        float tolerance = 2f / Math.min(width, height);
        if (left >= -tolerance && top >= -tolerance
                && right <= 1 + tolerance && bottom <= 1 + tolerance) return crop;
        if (left < -.2f || top < -.2f || right > 1.2f || bottom > 1.2f) return null;
        left -= tolerance;
        top -= tolerance;
        right += tolerance;
        bottom += tolerance;
        Matrix inverse = new Matrix();
        if (!plane.invert(inverse)) return null;
        float[] enclosure = {left, top, right, top, right, bottom, left, bottom};
        inverse.mapPoints(enclosure);
        for (int i = 0; i < enclosure.length; i += 2) {
            enclosure[i] /= width;
            enclosure[i + 1] /= height;
        }
        return CropQuad.from(enclosure);
    }

    private static boolean contains(CropQuad outer, CropQuad inner) {
        int contained = 0;
        for (int corner = 0; corner < 4; corner++) {
            boolean inside = true;
            for (int edge = 0; edge < 4; edge++) {
                int next = (edge + 1) % 4;
                double dx = outer.x(next) - outer.x(edge), dy = outer.y(next) - outer.y(edge);
                double cross = dx * (inner.y(corner) - outer.y(edge))
                        - dy * (inner.x(corner) - outer.x(edge));
                if (cross < -.005 * Math.hypot(dx, dy)) { inside = false; break; }
            }
            if (inside) contained++;
        }
        return contained >= 3;
    }

    private static CropQuad ordered(Point[] points, int width, int height) {
        double centerX = 0;
        double centerY = 0;
        for (Point point : points) {
            centerX += point.x / 4;
            centerY += point.y / 4;
        }
        final double x = centerX;
        final double y = centerY;
        Arrays.sort(points, Comparator.comparingDouble(p -> Math.atan2(p.y - y, p.x - x)));
        int first = 0;
        for (int i = 1; i < 4; i++) {
            if (points[i].x + points[i].y < points[first].x + points[first].y) first = i;
        }
        float[] result = new float[8];
        for (int i = 0; i < 4; i++) {
            Point point = points[(first + i) % 4];
            result[2 * i] = (float) (point.x / width);
            result[2 * i + 1] = (float) (point.y / height);
        }
        return CropQuad.from(result);
    }

    private static double edgeContrast(CropQuad crop, byte[] luminance, int width, int height) {
        double cx = (crop.x(0) + crop.x(1) + crop.x(2) + crop.x(3)) / 4;
        double cy = (crop.y(0) + crop.y(1) + crop.y(2) + crop.y(3)) / 4;
        double contrast = 0;
        int visibleSides = 0;
        int separatedSides = 0;
        int[] differences = new int[9];
        int[] patch = new int[9];
        int step = Math.max(1, Math.min(width, height) / 450);
        for (int corner = 0; corner < 4; corner++) {
            int next = (corner + 1) % 4;
            int samples = 0;
            for (int sample = 0; sample < differences.length; sample++) {
                double weight = .14 + .09 * sample;
                double x = crop.x(corner) + (crop.x(next) - crop.x(corner)) * weight;
                double y = crop.y(corner) + (crop.y(next) - crop.y(corner)) * weight;
                double dx = (cx - x) * .055, dy = (cy - y) * .055;
                int ix = (int) Math.round((x + dx) * width), iy = (int) Math.round((y + dy) * height);
                int ox = (int) Math.round((x - dx) * width), oy = (int) Math.round((y - dy) * height);
                if (Math.min(ix, ox) < step || Math.min(iy, oy) < step
                        || Math.max(ix, ox) >= width - step || Math.max(iy, oy) >= height - step) continue;
                differences[samples++] = backgroundTone(luminance, width, ix, iy, step, patch)
                        - backgroundTone(luminance, width, ox, oy, step, patch);
            }
            if (samples >= 5) {
                visibleSides++;
                // A page edge separates two surfaces along the side. Ink, grid rules and isolated
                // letters can produce high individual readings on a continuous paper surface.
                Arrays.sort(differences, 0, samples);
                int difference = Math.abs(differences[samples / 2]);
                if (difference >= 6) {
                    separatedSides++;
                    contrast += Math.min(65, difference);
                }
            }
        }
        if (separatedSides < Math.min(3, visibleSides) || separatedSides < 2) return 0;
        return contrast / visibleSides;
    }

    private static int backgroundTone(byte[] luminance, int width, int x, int y, int step, int[] patch) {
        int i = 0;
        for (int dy = -step; dy <= step; dy += step) {
            for (int dx = -step; dx <= step; dx += step) {
                patch[i++] = luminance[(y + dy) * width + x + dx] & 255;
            }
        }
        Arrays.sort(patch);
        return patch[6]; // Upper quartile ignores narrow strokes over the local paper tone.
    }

    private static boolean hasPrintedMargin(CropQuad crop, byte[] luminance, int width, int height) {
        // A shaded panel in a digital document can have four continuous, high-contrast edges.
        // Look for ink on the same uniform paper outside that panel before discarding the margins.
        int[] border = new int[64];
        for (int i = 0; i < 16; i++) {
            int x = (2 * i + 1) * width / 32, y = (2 * i + 1) * height / 32;
            border[i] = luminance[width + x] & 255;
            border[i + 16] = luminance[(height - 2) * width + x] & 255;
            border[i + 32] = luminance[y * width + 1] & 255;
            border[i + 48] = luminance[y * width + width - 2] & 255;
        }
        Arrays.sort(border);
        int paper = border[32];
        if (paper < 160 || border[56] - border[7] > 8) return false;

        float minX = 1, minY = 1, maxX = 0, maxY = 0;
        for (int i = 0; i < 4; i++) {
            minX = Math.min(minX, crop.x(i));
            maxX = Math.max(maxX, crop.x(i));
            minY = Math.min(minY, crop.y(i));
            maxY = Math.max(maxY, crop.y(i));
        }
        int pad = Math.max(2, Math.min(width, height) / 75);
        int left = (int) (minX * width) - pad, right = (int) (maxX * width) + pad;
        int top = (int) (minY * height) - pad, bottom = (int) (maxY * height) + pad;
        int step = Math.max(1, Math.max(width, height) / 550);
        int samples = 0, onPaper = 0, rowsWithInk = 0;
        for (int y = step; y < height - step; y += step) {
            boolean ink = false;
            int strokes = 0;
            for (int x = step; x < width - step; x += step) {
                if (x >= left && x <= right && y >= top && y <= bottom) {
                    ink = false;
                    continue;
                }
                int tone = luminance[y * width + x] & 255;
                samples++;
                if (Math.abs(tone - paper) <= 6) onPaper++;
                if (!ink && tone < paper - 45) strokes++;
                ink = tone < paper - 45;
            }
            if (strokes >= 4) rowsWithInk++;
        }
        return samples > 0 && onPaper >= .9 * samples && rowsWithInk >= 3;
    }

    private static void enhance(Bitmap bitmap, ScanDocument.Filter filter) throws IOException {
        if (filter == ScanDocument.Filter.SCREEN) {
            ScreenDescreen.apply(bitmap);
            return;
        }
        requireVision();
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        Mat rgba = new Mat();
        Mat gray = new Mat();
        Mat background = new Mat();
        Mat small = new Mat();
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE,
                new org.opencv.core.Size(17, 17));
        try {
            Utils.bitmapToMat(bitmap, rgba);
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);
            rgba.release();
            boolean automatic = filter == ScanDocument.Filter.AUTO;
            float shrink = Math.min(1, 156f / Math.max(width, height));
            Imgproc.resize(gray, small, new org.opencv.core.Size(
                    Math.max(1, Math.round(width * shrink)), Math.max(1, Math.round(height * shrink))),
                    0, 0, Imgproc.INTER_AREA);
            Imgproc.morphologyEx(small, small, Imgproc.MORPH_CLOSE, kernel);
            Imgproc.GaussianBlur(small, small, new org.opencv.core.Size(15, 15), 0);
            if (automatic) {
                estimateDocumentLight(gray, small, background);
                enhanceDocument(bitmap, gray, background);
                return;
            }
            Imgproc.resize(small, background, new org.opencv.core.Size(width, height));
            int[] pixels = new int[width];
            byte[] light = new byte[width];
            byte[] luma = new byte[width];
            for (int y = 0; y < height; y++) {
                bitmap.getPixels(pixels, 0, width, 0, y, width, 1);
                gray.get(y, 0, luma);
                background.get(y, 0, light);
                for (int x = 0; x < width; x++) {
                    float factor = Math.min(2.7f, 251f / Math.max(60, light[x] & 255));
                    int value = tone((luma[x] & 255) * factor);
                    if (filter == ScanDocument.Filter.COLOR) {
                        int color = pixels[x];
                        pixels[x] = Color.rgb(tone(Color.red(color) * factor),
                                tone(Color.green(color) * factor), tone(Color.blue(color) * factor));
                    } else {
                        int shade = filter == ScanDocument.Filter.MONO ? value >= 177 ? 255 : 0 : value;
                        pixels[x] = Color.rgb(shade, shade, shade);
                    }
                }
                bitmap.setPixels(pixels, 0, width, 0, y, width, 1);
            }
        } finally {
            release(rgba, gray, background, small, kernel);
        }
    }

    private static void estimateDocumentLight(Mat gray, Mat coarse, Mat result) {
        // The coarse envelope spans large printed labels but blurs a cast shadow's edge.
        // Recover those edges on an ink-suppressed, higher-resolution paper image.
        double scale = Math.min(1, 720.0 / Math.max(gray.rows(), gray.cols()));
        int w = Math.max(1, (int) Math.round(gray.cols() * scale));
        int h = Math.max(1, (int) Math.round(gray.rows() * scale));
        int radius = Math.max(2, Math.round(Math.max(w, h) / 115f));
        Mat fine = new Mat();
        Mat filtered = new Mat();
        Mat broad = new Mat();
        Mat solid = new Mat();
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE,
                new org.opencv.core.Size(2 * radius + 1, 2 * radius + 1));
        try {
            Imgproc.resize(gray, fine, new org.opencv.core.Size(w, h), 0, 0, Imgproc.INTER_AREA);
            // Suppress reversed white lettering when looking for a solid dark printed block.
            Imgproc.morphologyEx(fine, solid, Imgproc.MORPH_OPEN, kernel);
            Imgproc.morphologyEx(solid, solid, Imgproc.MORPH_CLOSE, kernel);
            Imgproc.morphologyEx(fine, fine, Imgproc.MORPH_CLOSE, kernel);
            Imgproc.bilateralFilter(fine, filtered, 7, 10, 3);
            Imgproc.resize(coarse, broad, filtered.size());
            byte[] detail = new byte[w], reference = new byte[w], fill = new byte[w];
            for (int y = 0; y < h; y++) {
                filtered.get(y, 0, detail);
                broad.get(y, 0, reference);
                solid.get(y, 0, fill);
                for (int x = 0; x < w; x++) {
                    int local = detail[x] & 255, wide = reference[x] & 255;
                    // Large, near-black ink islands can survive the first closing.
                    // Blend back to the wider estimate only when the local level is implausibly low.
                    int fillLevel = Math.min(local, fill[x] & 255);
                    float ink = Math.max(0f, Math.min(1f, (wide * .39f - fillLevel) / Math.max(1f, wide * .17f)));
                    detail[x] = (byte) Math.round(local + ink * (wide - local));
                }
                filtered.put(y, 0, detail);
            }
            Imgproc.resize(filtered, result, gray.size(), 0, 0, Imgproc.INTER_LINEAR);
            Imgproc.morphologyEx(filtered, broad, Imgproc.MORPH_GRADIENT, kernel);
            alignDocumentLight(gray, broad, result);
        } finally {
            release(fine, filtered, broad, solid, kernel);
        }
    }

    private static void alignDocumentLight(Mat gray, Mat gradient, Mat result) {
        // Re-align the upsampled estimate only near illumination edges. A closing at the
        // original resolution excludes thin ink from the guide, including crossed table rules.
        int width = gray.cols(), height = gray.rows();
        int radius = Math.max(1, Math.min(4, Math.round(Math.max(width, height) / 700f)));
        Mat guide = new Mat();
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE,
                new org.opencv.core.Size(2 * radius + 1, 2 * radius + 1));
        try {
            Imgproc.morphologyEx(gray, guide, Imgproc.MORPH_CLOSE, kernel);
            byte[] paper = new byte[width], light = new byte[width];
            byte[] edge = new byte[gradient.cols()];
            int[] column = new int[width];
            for (int x = 0; x < width; x++) column[x] = x * edge.length / width;
            int smallHeight = gradient.rows();
            for (int y = 0; y < height; y++) {
                gradient.get(y * smallHeight / height, 0, edge);
                guide.get(y, 0, paper);
                result.get(y, 0, light);
                for (int x = 0; x < width; x++) {
                    int span = edge[column[x]] & 255;
                    if (span <= 12) continue;
                    int estimate = light[x] & 255;
                    int difference = (paper[x] & 255) - estimate;
                    float trust = Math.min(1f, (span - 12) / 30f)
                            * Math.max(0f, Math.min(1f, (36f - Math.abs(difference)) / 18f));
                    light[x] = (byte) Math.round(estimate + trust * difference);
                }
                result.put(y, 0, light);
            }
        } finally {
            release(guide, kernel);
        }
    }

    private static void enhanceDocument(Bitmap bitmap, Mat gray, Mat paper) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        Mat smooth = new Mat();
        Mat reflectance = new Mat();
        try {
            // Sharpen the ink-to-paper ratio, so a change in lighting does not get sharpened
            // into a dark line. Use the same physical stroke width at preview and export sizes.
            double sigma = Math.max(.55, Math.min(1.8, Math.max(width, height) / 1050.0));
            Core.divide(gray, paper, reflectance, 245.0);
            Imgproc.GaussianBlur(reflectance, smooth, new org.opencv.core.Size(0, 0), sigma);
            float[] gains = new float[256];
            for (int i = 0; i < gains.length; i++) {
                // Extremely dark regions have little recoverable signal. Limit their noise gain.
                float signal = Math.max(0, Math.min(1, (i - 18) / 44f));
                float limit = 1f + 4.2f * signal * signal * (3f - 2f * signal);
                gains[i] = 1f + .97f * (Math.max(1f, Math.min(limit, 246f / Math.max(1, i))) - 1f);
            }
            int[] colors = new int[width];
            byte[] light = new byte[width];
            byte[] pixels = new byte[width];
            byte[] blurred = new byte[width];
            byte[] normalized = new byte[width];
            for (int y = 0; y < height; y++) {
                bitmap.getPixels(colors, 0, width, 0, y, width, 1);
                paper.get(y, 0, light);
                gray.get(y, 0, pixels);
                smooth.get(y, 0, blurred);
                reflectance.get(y, 0, normalized);
                for (int x = 0; x < width; x++) {
                    int value = pixels[x] & 255;
                    int backdrop = light[x] & 255;
                    int color = colors[x];
                    int r = Color.red(color), g = Color.green(color), b = Color.blue(color);
                    int peak = Math.max(r, Math.max(g, b));
                    float gain = Math.min(gains[backdrop], 255f / Math.max(1, peak));
                    // In boosted shadows, reduce colored sensor speckle on nearly neutral paper.
                    // A saturated highlighter or seal retains its original channel proportions.
                    int chroma = peak - Math.min(r, Math.min(g, b));
                    float neutral = Math.max(0, Math.min(1, (peak * .21f - chroma) / Math.max(1, peak * .13f)));
                    float desaturate = .78f * neutral * Math.min(1, gain - 1f);
                    r += Math.round((value - r) * desaturate);
                    g += Math.round((value - g) * desaturate);
                    b += Math.round((value - b) * desaturate);
                    // Reinforce the dark side of strokes without drawing bright outlines.
                    float delta = ((blurred[x] & 255) - (normalized[x] & 255)) * backdrop / 245f;
                    float edge = Math.max(0, delta - 3);
                    float ink = Math.max(0, backdrop - value - 5);
                    float deepen = Math.min(22f, gain * (.62f * edge + .12f * ink));
                    float scale = gain - Math.min(value * gain, deepen) / Math.max(1, value);
                    int denoise = Math.round(.55f * neutral * Math.min(1, gain - 1f) * delta
                            * Math.max(0, 1f - Math.abs(delta) / 8f));
                    colors[x] = Color.rgb(channel(r, scale, denoise), channel(g, scale, denoise),
                            channel(b, scale, denoise));
                }
                bitmap.setPixels(colors, 0, width, 0, y, width, 1);
            }
        } finally {
            release(smooth, reflectance);
        }
    }

    private static int channel(int value, float scale, int offset) {
        return Math.max(0, Math.min(255, Math.round(value * scale) + offset));
    }

    private static int tone(float value) {
        return Math.max(0, Math.min(255, Math.round((value - 28) * 1.12f + 19)));
    }

    static void release(Mat... mats) {
        for (Mat mat : mats) mat.release();
    }

    private static final class Candidate {
        final CropQuad crop;
        final CropQuad original;
        final double area;
        final double score;
        final boolean preservesContour;

        Candidate(CropQuad crop, CropQuad original, double area, double score, boolean preservesContour) {
            this.crop = crop;
            this.original = original;
            this.area = area;
            this.score = score;
            this.preservesContour = preservesContour;
        }
    }
}
