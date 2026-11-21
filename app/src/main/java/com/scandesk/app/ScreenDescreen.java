package com.scandesk.app;

import android.graphics.Bitmap;

/** Conservative chroma-only cleanup for recaptured slides and documents. */
final class ScreenDescreen {
    private ScreenDescreen() {}

    static void apply(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int step = Math.max(6, Math.round(Math.max(width, height) / 84f));
        int columns = (width + step - 1) / step;
        int rows = (height + step - 1) / step;
        Patch[] grid = new Patch[columns * rows];
        for (int i = 0; i < grid.length; i++) grid[i] = new Patch();
        int[] previous = new int[width];
        int[] pixels = new int[width];
        for (int y = 0; y < height; y++) {
            bitmap.getPixels(pixels, 0, width, 0, y, width, 1);
            for (int x = 0; x < width; x++) {
                int color = pixels[x];
                if (spread(color) >= 96) continue;
                Patch patch = grid[y / step * columns + x / step];
                int g = (color >> 8) & 255;
                int u = ((color >> 16) & 255) - g, v = (color & 255) - g;
                patch.count++;
                patch.sumU += u;
                patch.sumV += v;
                patch.absolute += Math.abs(u) + Math.abs(v);
                patch.squares += u * u + v * v;
                int light = luma(color);
                patch.sumLight += light;
                if (x > 0) patch.across += colorChange(pixels[x - 1], u, v, light);
                if (y > 0) patch.down += colorChange(previous[x], u, v, light);
            }
            int[] swap = previous;
            previous = pixels;
            pixels = swap;
        }
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < columns; x++) {
                Patch area = new Patch();
                for (int j = Math.max(0, y-1); j <= Math.min(rows-1, y+1); j++) {
                    for (int i = Math.max(0, x-1); i <= Math.min(columns-1, x+1); i++) {
                        area.add(grid[j * columns + i]);
                    }
                }
                grid[y * columns + x].measure(area, step);
            }
        }
        // Inspect unchanged source neighbours even after earlier rows have been filtered.
        int[] current = new int[width];
        int[] next = new int[width];
        bitmap.getPixels(current, 0, width, 0, 0, width, 1);
        for (int y = 0; y < height; y++) {
            if (y + 1 < height) bitmap.getPixels(next, 0, width, 0, y + 1, width, 1);
            System.arraycopy(current, 0, pixels, 0, width);
            float gy = Math.max(0, (y + .5f) / step - .5f);
            int top = (int) gy, bottom = Math.min(top + 1, rows - 1);
            for (int x = 0; x < width; x++) {
                int color = current[x];
                int saturation = spread(color);
                if (saturation == 0 || saturation >= 96) continue;
                float gx = Math.max(0, (x + .5f) / step - .5f);
                int left = (int) gx, right = Math.min(left + 1, columns - 1);
                Patch a = grid[top * columns + left], b = grid[top * columns + right];
                Patch c = grid[bottom * columns + left], d = grid[bottom * columns + right];
                float fx = gx - left, fy = gy - top;
                float strength = blend(a.strength, b.strength, c.strength, d.strength, fx, fy);
                if (strength < .005f) continue;
                int g = (color >> 8) & 255;
                int redGreen = ((color >> 16) & 255) - g, blueGreen = (color & 255) - g;
                int pixelLight = luma(color);
                // Tile confidence can reach across a graphic. Its constant-colour interior
                // has no oscillation, and a contrasting edge alone is not a colour band.
                if ((x == 0 || colorChange(current[x - 1], redGreen, blueGreen, pixelLight) == 0)
                        && (x + 1 == width || colorChange(current[x + 1], redGreen, blueGreen, pixelLight) == 0)
                        && (y == 0 || colorChange(previous[x], redGreen, blueGreen, pixelLight) == 0)
                        && (y + 1 == height || colorChange(next[x], redGreen, blueGreen, pixelLight) == 0)) continue;
                float light = blend(a.light, b.light, c.light, d.light, fx, fy);
                strength *= clampUnit((96 - saturation) / 32f)
                        * clampUnit((92 - Math.abs(pixelLight - light)) / 46f);
                float u = blend(a.u, b.u, c.u, d.u, fx, fy);
                float v = blend(a.v, b.v, c.v, d.v, fx, fy);
                pixels[x] = reduceChroma(color, strength, u, v);
            }
            bitmap.setPixels(pixels, 0, width, 0, y, width, 1);
            int[] swap = previous;
            previous = current;
            current = next;
            next = swap;
        }
    }

    private static int spread(int color) {
        int r = (color >> 16) & 255, g = (color >> 8) & 255, b = color & 255;
        return Math.max(r, Math.max(g,b)) - Math.min(r, Math.min(g,b));
    }

    private static int luma(int color) {
        return (77 * ((color >> 16) & 255) + 150 * ((color >> 8) & 255) + 29 * (color & 255) + 128) >> 8;
    }

    private static int colorChange(int previous, int u, int v, int light) {
        if (spread(previous) >= 96 || Math.abs(luma(previous) - light) > 30) return 0;
        int g = (previous >> 8) & 255;
        return Math.abs(((previous >> 16) & 255) - g - u) + Math.abs((previous & 255) - g - v);
    }

    private static float clampUnit(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static float blend(float a, float b, float c, float d, float x, float y) {
        return (a + x * (b - a)) * (1-y) + (c + x * (d - c)) * y;
    }

    private static int reduceChroma(int color, float strength, float meanU, float meanV) {
        int r = (color >> 16) & 255, g = (color >> 8) & 255, b = color & 255;
        int u = r - g, v = b - g;
        // Only remove a tint that is already present. Never bleed a neighbour's hue into paper.
        float du = strength * (Math.max(Math.min(0,u), Math.min(Math.max(0,u), meanU)) - u);
        float dv = strength * (Math.max(Math.min(0,v), Math.min(Math.max(0,v), meanV)) - v);
        float dg = -(77 * du + 29 * dv) / 256f;
        return (color & 0xff000000) | channel(r + du + dg) << 16 | channel(g + dg) << 8 | channel(b + dv + dg);
    }

    private static int channel(float value) {
        return Math.max(0, Math.min(255, Math.round(value)));
    }

    private static final class Patch {
        int count, sumU, sumV, sumLight, absolute, squares, across, down;
        double within;
        float u, v, light, strength;

        void add(Patch p) {
            if (p.count == 0) return;
            count += p.count;
            sumU += p.sumU;
            sumV += p.sumV;
            sumLight += p.sumLight;
            absolute += p.absolute;
            squares += p.squares;
            within += p.squares - ((double) p.sumU * p.sumU + (double) p.sumV * p.sumV) / p.count;
            across += p.across;
            down += p.down;
        }

        void measure(Patch area, int step) {
            if (area.count == 0) return;
            u = area.sumU / (float) area.count;
            v = area.sumV / (float) area.count;
            light = area.sumLight / (float) area.count;
            float amplitude = area.absolute / (float) area.count;
            float opposingTints = amplitude - Math.abs(u) - Math.abs(v);
            float variance = Math.max(0, area.squares / (float) area.count - u*u - v*v);
            float rms = (float) Math.sqrt(variance);
            float repeated = (float) (area.within / (area.count * Math.max(1f, variance)));
            // A single pastel edge has variation in only one transition. Repeated bands have
            // both opposing tints and several transitions inside the same neighbourhood.
            float variation = Math.max(area.across, area.down) / (float) area.count;
            float oscillations = variation * (3 * step) / Math.max(8f, Math.max(rms, amplitude));
            strength = .94f * clampUnit((rms - 4f) / 8f) * clampUnit(opposingTints / 4f)
                    * clampUnit((oscillations - 3.2f) / 2.5f) * clampUnit((repeated - .35f) / .25f);
        }
    }
}
