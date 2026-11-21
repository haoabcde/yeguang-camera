package com.scandesk.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

@SuppressLint("ViewConstructor") // Created by the editor and the review dialog.
final class ScanPreview extends View {
    interface CropListener {
        void changed(CropQuad crop);
    }

    private final ScanUi ui;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF imageRect = new RectF();
    private final Path outline = new Path();
    private final ScaleGestureDetector scaling;
    private final GestureDetector gestures;
    private Bitmap bitmap;
    private CropQuad crop;
    private CropListener cropListener;
    private int dragCorner = -1;
    private int dragPointer = -1;
    private float downX, downY;
    private float initialCornerX, initialCornerY;
    private float zoom = 1;
    private float shiftX;
    private float shiftY;

    ScanPreview(Context context, ScanUi ui) {
        super(context);
        this.ui = ui;
        setBackgroundColor(ScanUi.BOARD);
        setContentDescription("文档预览，双指缩放");
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        scaling = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                float old = zoom;
                zoom = Math.max(1, Math.min(4, old * detector.getScaleFactor()));
                if (old != zoom) {
                    shiftX += (detector.getFocusX() - getWidth() / 2f - shiftX) * (1 - zoom / old);
                    shiftY += (detector.getFocusY() - getHeight() / 2f - shiftY) * (1 - zoom / old);
                    invalidate();
                }
                return true;
            }
        });
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }

            @Override public boolean onDoubleTap(MotionEvent e) {
                zoom = zoom > 1 ? 1 : 2;
                shiftX = 0;
                shiftY = 0;
                invalidate();
                return true;
            }

            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float x, float y) {
                if (zoom <= 1 || scaling.isInProgress()) return false;
                shiftX -= x;
                shiftY -= y;
                invalidate();
                return true;
            }
        });
    }

    void show(Bitmap bitmap, CropQuad crop, CropListener listener) {
        show(bitmap, crop, listener, false);
    }

    void show(Bitmap bitmap, CropQuad crop, CropListener listener, boolean keepViewport) {
        if (this.bitmap != bitmap && !keepViewport) {
            zoom = 1;
            shiftX = 0;
            shiftY = 0;
        }
        this.bitmap = bitmap;
        this.crop = crop;
        this.cropListener = listener;
        setContentDescription(crop == null ? "文档预览，双指缩放" : "裁切预览，拖动四角调整区域");
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap == null) return;
        measureImage();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(ScanUi.WHITE);
        canvas.drawBitmap(bitmap, null, imageRect, paint);
        if (crop == null) return;
        outline.reset();
        outline.moveTo(x(0), y(0));
        for (int i = 1; i < 4; i++) outline.lineTo(x(i), y(i));
        outline.close();
        canvas.save();
        canvas.clipRect(imageRect);
        canvas.clipOutPath(outline);
        canvas.drawColor(0x1c000000);
        canvas.restore();
        paint.setStrokeWidth(ui.dp(2));
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(ScanUi.CROP);
        canvas.drawPath(outline, paint);
        for (int i = 0; i < 4; i++) {
            float radius = ui.dp(i == dragCorner ? 8 : 6);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(ScanUi.PAPER);
            canvas.drawCircle(x(i), y(i), radius, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(ScanUi.CROP);
            canvas.drawCircle(x(i), y(i), radius, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        if (dragCorner >= 0) drawCornerLoupe(canvas);
    }

    private void drawCornerLoupe(Canvas canvas) {
        float radius = Math.min(ui.dp(44), Math.min(getWidth(), getHeight()) / 5f);
        if (radius < ui.dp(20)) return;
        float cx = crop.x(dragCorner) < .5f ? getWidth() - radius - ui.dp(16) : radius + ui.dp(16);
        float cy = radius + ui.dp(16);
        Path lens = new Path();
        lens.addCircle(cx, cy, radius, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(lens);
        canvas.drawColor(ScanUi.PAPER);
        canvas.translate(cx, cy);
        float scale = 3 * imageRect.width() / bitmap.getWidth();
        canvas.scale(scale, scale);
        canvas.drawBitmap(bitmap, -crop.x(dragCorner) * bitmap.getWidth(),
                -crop.y(dragCorner) * bitmap.getHeight(), paint);
        canvas.restore();
        paint.setColor(ScanUi.CROP);
        paint.setStrokeWidth(ui.dp(2));
        paint.setStyle(Paint.Style.STROKE);
        canvas.drawCircle(cx, cy, radius, paint);
        canvas.drawLine(cx - ui.dp(9), cy, cx + ui.dp(9), cy, paint);
        canvas.drawLine(cx, cy - ui.dp(9), cx, cy + ui.dp(9), paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void measureImage() {
        int margin = ui.dp(12);
        float fit = Math.min(Math.max(1, getWidth() - 2 * margin) / (float) bitmap.getWidth(),
                Math.max(1, getHeight() - 2 * margin) / (float) bitmap.getHeight());
        float width = bitmap.getWidth() * fit * zoom;
        float height = bitmap.getHeight() * fit * zoom;
        float maxX = Math.max(0, (width - getWidth() + 2 * margin) / 2);
        float maxY = Math.max(0, (height - getHeight() + 2 * margin) / 2);
        shiftX = Math.max(-maxX, Math.min(maxX, shiftX));
        shiftY = Math.max(-maxY, Math.min(maxY, shiftY));
        float left = (getWidth() - width) / 2 + shiftX;
        float top = (getHeight() - height) / 2 + shiftY;
        imageRect.set(left, top, left + width, top + height);
    }

    private float x(int corner) { return imageRect.left + crop.x(corner) * imageRect.width(); }
    private float y(int corner) { return imageRect.top + crop.y(corner) * imageRect.height(); }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (bitmap == null) return false;
        int action = event.getActionMasked();
        if (crop != null && action == MotionEvent.ACTION_DOWN) {
            measureImage();
            double nearest = ui.dp(26);
            for (int i = 0; i < 4; i++) {
                double distance = Math.hypot(x(i) - event.getX(), y(i) - event.getY());
                if (distance < nearest) {
                    nearest = distance;
                    dragCorner = i;
                }
            }
            if (dragCorner >= 0) {
                dragPointer = event.getPointerId(0);
                downX = event.getX();
                downY = event.getY();
                initialCornerX = crop.x(dragCorner);
                initialCornerY = crop.y(dragCorner);
                getParent().requestDisallowInterceptTouchEvent(true);
                invalidate();
                return true;
            }
        }
        if (dragCorner >= 0) {
            int pointer = dragPointer >= 0 ? event.findPointerIndex(dragPointer) : -1;
            boolean lifted = action == MotionEvent.ACTION_POINTER_UP && pointer == event.getActionIndex();
            if (pointer >= 0 && (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP || lifted)) {
                float dx = (event.getX(pointer) - downX) / imageRect.width();
                float dy = (event.getY(pointer) - downY) / imageRect.height();
                CropQuad adjusted = crop.move(dragCorner, initialCornerX + dx, initialCornerY + dy);
                if (!crop.equals(adjusted)) {
                    crop = adjusted;
                    cropListener.changed(crop);
                    invalidate();
                }
            }
            if (lifted) dragPointer = -1;
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                dragCorner = -1;
                dragPointer = -1;
                getParent().requestDisallowInterceptTouchEvent(false);
                invalidate();
                if (action == MotionEvent.ACTION_UP) performClick();
            }
            return true;
        }
        scaling.onTouchEvent(event);
        gestures.onTouchEvent(event);
        if (action == MotionEvent.ACTION_UP) performClick();
        return true;
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }
}
