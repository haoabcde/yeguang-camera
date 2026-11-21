package com.scandesk.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.Consumer;

final class LocalRepairDialog {
    private LocalRepairDialog() {}

    /** Takes ownership of the preview. On confirmation the caller owns the resulting mask. */
    static void show(Activity activity, ScanUi ui, Bitmap preview, Consumer<Bitmap> maskReady) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            preview.recycle();
            return;
        }
        LinearLayout panel = ui.vertical();
        panel.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), 0);
        TextView instructions = ui.text("涂抹小污点或划痕，系统会用周围画面修补", 14, ScanUi.SECONDARY);
        instructions.setPadding(0, 0, 0, ui.dp(12));
        panel.addView(instructions);
        BrushView brush = new BrushView(activity, ui, preview);
        int height = Math.min(370, Math.max(110, activity.getResources().getConfiguration().screenHeightDp - 220));
        panel.addView(brush, new LinearLayout.LayoutParams(ui.match(), ui.dp(height)));

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("手动消除")
                .setView(panel)
                .setNeutralButton("清空", null)
                .setNegativeButton("取消", null)
                .setPositiveButton("导出 PNG", null)
                .create();
        dialog.setOnDismissListener(ignored -> brush.dispose());
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            brush.onStrokeChanged = hasStroke -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(hasStroke);
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> brush.clear());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                Bitmap mask = brush.mask();
                dialog.dismiss();
                maskReady.accept(mask);
            });
        });
        dialog.show();
    }

    private static final class BrushView extends View {
        private final RectF destination = new RectF();
        private final Path strokes = new Path();
        private final Paint picture = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint highlight = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float brushSize;
        private Bitmap preview;
        private float scale;
        private int pointer = -1;
        private boolean hasStroke;
        Consumer<Boolean> onStrokeChanged = ignored -> {};

        BrushView(Context context, ScanUi ui, Bitmap preview) {
            super(context);
            this.preview = preview;
            brushSize = ui.dp(25);
            setBackground(ui.panel(ScanUi.SURFACE, 9, ScanUi.LINE));
            setClipToOutline(true);
            setContentDescription("图像修补涂抹区");
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            highlight.setColor(0x982054f3);
            highlight.setStyle(Paint.Style.STROKE);
            highlight.setStrokeCap(Paint.Cap.ROUND);
            highlight.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            if (preview == null) return;
            scale = Math.min(width / (float) preview.getWidth(), height / (float) preview.getHeight());
            float imageWidth = preview.getWidth() * scale;
            float imageHeight = preview.getHeight() * scale;
            float left = (width - imageWidth) / 2;
            float top = (height - imageHeight) / 2;
            destination.set(left, top, left + imageWidth, top + imageHeight);
            highlight.setStrokeWidth(scale > 0 ? brushSize / scale : brushSize);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (preview == null || scale <= 0) return;
            canvas.drawBitmap(preview, null, destination, picture);
            int restore = canvas.save();
            canvas.clipRect(destination);
            canvas.translate(destination.left, destination.top);
            canvas.scale(scale, scale);
            canvas.drawPath(strokes, highlight);
            canvas.restoreToCount(restore);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (preview == null || scale <= 0) return false;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                float x = event.getX();
                float y = event.getY();
                if (!destination.contains(x, y)) return false;
                pointer = event.getPointerId(0);
                strokes.moveTo(imageX(x), imageY(y));
                strokes.lineTo(imageX(x) + .01f, imageY(y));
                if (!hasStroke) {
                    hasStroke = true;
                    onStrokeChanged.accept(true);
                }
                invalidate();
                return true;
            }
            if (action == MotionEvent.ACTION_CANCEL) {
                pointer = -1;
                return true;
            }
            int index = event.findPointerIndex(pointer);
            if (index < 0) return true;
            boolean ending = action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_POINTER_UP && event.getActionIndex() == index;
            if (action == MotionEvent.ACTION_MOVE || ending) {
                for (int i = 0; i < event.getHistorySize(); i++) {
                    strokes.lineTo(imageX(event.getHistoricalX(index, i)), imageY(event.getHistoricalY(index, i)));
                }
                strokes.lineTo(imageX(event.getX(index)), imageY(event.getY(index)));
                invalidate();
            }
            if (ending) {
                pointer = -1;
                performClick();
            }
            return true;
        }

        @Override public boolean performClick() {
            return super.performClick();
        }

        private float imageX(float x) {
            return Math.max(0, Math.min((x - destination.left) / scale, preview.getWidth() - 1));
        }

        private float imageY(float y) {
            return Math.max(0, Math.min((y - destination.top) / scale, preview.getHeight() - 1));
        }

        void clear() {
            pointer = -1;
            hasStroke = false;
            strokes.reset();
            onStrokeChanged.accept(false);
            invalidate();
        }

        Bitmap mask() {
            Bitmap mask = Bitmap.createBitmap(preview.getWidth(), preview.getHeight(), Bitmap.Config.ARGB_8888);
            Paint ink = new Paint(highlight);
            ink.setColor(Color.WHITE);
            Canvas canvas = new Canvas(mask);
            try {
                canvas.drawPath(strokes, ink);
            } finally {
                canvas.setBitmap(null);
            }
            return mask;
        }

        void dispose() {
            if (preview != null) {
                preview.recycle();
                preview = null;
            }
        }
    }
}
