package com.scandesk.app;

import java.util.Arrays;

/** Clockwise points, starting at the top left, in normalized source coordinates. */
final class CropQuad {
    static final CropQuad FULL = new CropQuad(new float[]{0, 0, 1, 0, 1, 1, 0, 1});
    private final float[] points;

    private CropQuad(float[] points) {
        this.points = points;
    }

    static CropQuad from(float[] points) {
        if (points == null || points.length != 8) return null;
        for (float point : points) {
            if (!Float.isFinite(point) || point < 0 || point > 1) return null;
        }
        double area = 0;
        for (int i = 0; i < 8; i += 2) {
            int j = (i + 2) % 8;
            int k = (i + 4) % 8;
            double ax = points[j] - points[i];
            double ay = points[j + 1] - points[i + 1];
            double bx = points[k] - points[j];
            double by = points[k + 1] - points[j + 1];
            if (ax * by - ay * bx < 0.0001 || Math.hypot(ax, ay) < 0.025) return null;
            area += points[i] * points[j + 1] - points[i + 1] * points[j];
        }
        return area < 0.012 ? null : new CropQuad(points.clone());
    }

    float x(int corner) {
        return points[corner * 2];
    }

    float y(int corner) {
        return points[corner * 2 + 1];
    }

    float[] values() {
        return points.clone();
    }

    float[] pixels(int width, int height) {
        float[] scaled = values();
        for (int i = 0; i < scaled.length; i += 2) {
            scaled[i] *= width;
            scaled[i + 1] *= height;
        }
        return scaled;
    }

    CropQuad move(int corner, float x, float y) {
        float[] moved = values();
        moved[corner * 2] = Math.max(0, Math.min(1, x));
        moved[corner * 2 + 1] = Math.max(0, Math.min(1, y));
        CropQuad candidate = from(moved);
        return candidate == null ? this : candidate;
    }

    CropQuad clockwise() {
        float[] rotated = new float[8];
        for (int i = 0; i < 4; i++) {
            int target = (i + 1) % 4;
            rotated[target * 2] = 1 - y(i);
            rotated[target * 2 + 1] = x(i);
        }
        return new CropQuad(rotated);
    }

    @Override public boolean equals(Object other) {
        return other instanceof CropQuad && Arrays.equals(points, ((CropQuad) other).points);
    }

    @Override public int hashCode() {
        return Arrays.hashCode(points);
    }
}
