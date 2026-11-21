package com.scandesk.app;

import android.graphics.Bitmap;

/** Conservative hints for the camera's saved, correctly oriented preview. */
final class CaptureQuality {
    enum Issue { NONE, DARK, BLUR }

    private CaptureQuality() {}

    static Issue inspect(Bitmap photo) {
        int width = photo.getWidth();
        int height = photo.getHeight();
        if (width < 48 || height < 48) return Issue.NONE;
        byte[] light = new byte[width * height];
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            photo.getPixels(row, 0, width, 0, y, width, 1);
            for (int x = 0; x < width; x++) {
                int c = row[x];
                int value = (77 * (c >> 16 & 255) + 150 * (c >> 8 & 255) + 29 * (c & 255)) >> 8;
                light[y * width + x] = (byte) value;
            }
        }

        // Skip the frame edges, which often contain a dark desk or the edge of the paper.
        int left = Math.max(11, width / 10), top = Math.max(11, height / 10);
        int innerWidth = width - 2 * left, innerHeight = height - 2 * top;
        int dark = 0, detailed = 0, soft = 0, coarseDetailed = 0, coarseSoft = 0;
        for (int by = 0; by < 4; by++) {
            for (int bx = 0; bx < 4; bx++) {
                int sharpPeaks = 0, softPeaks = 0;
                int coarseSharp = 0, coarseBlur = 0;
                int xEnd = left + innerWidth * (bx + 1) / 4;
                int yEnd = top + innerHeight * (by + 1) / 4;
                for (int y = top + innerHeight * by / 4; y < yEnd; y++) {
                    for (int x = left + innerWidth * bx / 4; x < xEnd; x++) {
                        int p = y * width + x;
                        if ((light[p] & 255) < 48) dark++;
                        int horizontal = stroke(light, p, 1);
                        int vertical = stroke(light, p, width);
                        if (horizontal == 1 || vertical == 1) sharpPeaks++;
                        else if (horizontal == -1 || vertical == -1) softPeaks++;
                        if ((x & 1) == 0 && (y & 1) == 0) {
                            int across = stroke(light, p, 2), down = stroke(light, p, 2 * width);
                            if (across == 1 || down == 1) coarseSharp++;
                            else if (across == -1 || down == -1) coarseBlur++;
                        }
                    }
                }
                if (sharpPeaks + softPeaks >= Math.max(12, innerWidth * innerHeight / 13000)) {
                    detailed++;
                    if (softPeaks > 3 * sharpPeaks) soft++;
                }
                if (coarseSharp + coarseBlur >= Math.max(9, innerWidth * innerHeight / 50000)) {
                    coarseDetailed++;
                    if (coarseBlur > 3 * coarseSharp) coarseSoft++;
                }
            }
        }
        if (dark > (long) innerWidth * innerHeight * .97) return Issue.DARK;
        // A blank page, gradual lighting changes, or one soft edge are insufficient evidence.
        boolean softDetail = soft >= 4 && soft * 4 >= detailed * 3;
        boolean softOutline = detailed - soft <= 1 && coarseSoft >= 4 && coarseSoft * 4 >= coarseDetailed * 3;
        return softDetail || softOutline ? Issue.BLUR : Issue.NONE;
    }

    // At each edge peak compare the two-pixel gradient to the same transition at ten pixels.
    // A sharp dark stroke on light paper may have both outer samples on the paper.
    private static int stroke(byte[] light, int p, int step) {
        int near = Math.abs((light[p - step] & 255) - (light[p + step] & 255));
        if (near < 4) return 0;
        int center = light[p] & 255;
        int previous = Math.abs((light[p - 2 * step] & 255) - center);
        int next = Math.abs(center - (light[p + 2 * step] & 255));
        if (near < previous || near <= next) return 0;
        int wide = Math.abs((light[p - 5 * step] & 255) - (light[p + 5 * step] & 255));
        if (near >= 18 && near * 10 >= wide * 6) return 1;
        return wide >= 34 && near * 10 <= wide * 4 ? -1 : 0;
    }
}
