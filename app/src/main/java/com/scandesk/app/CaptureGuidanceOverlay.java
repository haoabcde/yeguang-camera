package com.scandesk.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Preview-coordinate paper outline. It never intercepts focus or shutter gestures. */
final class CaptureGuidanceOverlay extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path paper = new Path();
    private float[] points;
    private boolean ready;

    CaptureGuidanceOverlay(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setClickable(false);
        setFocusable(false);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    void show(float[] coordinates, boolean stable) {
        points = coordinates == null ? null : coordinates.clone();
        ready = stable;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        if (points == null || points.length != 8) return;
        paper.reset();
        paper.moveTo(points[0], points[1]);
        for (int i = 2; i < points.length; i += 2) paper.lineTo(points[i], points[i + 1]);
        paper.close();
        float density = getResources().getDisplayMetrics().density;
        paint.setStrokeWidth(4 * density);
        paint.setColor(0x66000000);
        canvas.drawPath(paper, paint);
        paint.setStrokeWidth(2 * density);
        paint.setColor(ready ? 0xfffbfaf7 : 0xffe6bc8d);
        canvas.drawPath(paper, paint);
    }
}
