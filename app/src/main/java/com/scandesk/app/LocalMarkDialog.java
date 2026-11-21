package com.scandesk.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.InputFilter;
import android.text.InputType;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
import java.util.function.Consumer;

final class LocalMarkDialog {
    private LocalMarkDialog() {}

    static void watermark(Activity activity, ScanUi ui, Consumer<Bitmap> complete) {
        LinearLayout panel = panel(ui);
        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(2 * LocalImages.WATERMARK_TEXT_LIMIT)});
        input.setHint("输入水印文字");
        panel.addView(input, new LinearLayout.LayoutParams(ui.match(), ui.wrap()));

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("添加文字水印")
                .setView(panel)
                .setNegativeButton("取消", null)
                .setPositiveButton("继续", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            input.requestFocus();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                Bitmap mark;
                try {
                    mark = LocalImages.watermark(input.getText().toString());
                } catch (IOException invalid) {
                    input.setError(invalid.getMessage());
                    return;
                }
                dialog.dismiss();
                complete.accept(mark);
            });
        });
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        dialog.show();
    }

    static void signature(Activity activity, ScanUi ui, Consumer<Bitmap> complete) {
        LinearLayout panel = panel(ui);
        TextView instructions = ui.text("在框内书写，签名将作为图样添加到页面右下角", 14, ScanUi.SECONDARY);
        LinearLayout.LayoutParams prompt = new LinearLayout.LayoutParams(ui.match(), ui.wrap());
        prompt.bottomMargin = ui.dp(12);
        panel.addView(instructions, prompt);
        int padHeight = Math.min(184, Math.max(96, activity.getResources().getConfiguration().screenHeightDp - 210));
        SignaturePad pad = new SignaturePad(activity, ui);
        panel.addView(pad, new LinearLayout.LayoutParams(ui.match(), ui.dp(padHeight)));

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("手写签名")
                .setView(panel)
                .setNeutralButton("清空", null)
                .setNegativeButton("取消", null)
                .setPositiveButton("继续", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            pad.onInkChanged = hasInk -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(hasInk);
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> pad.clear());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                Bitmap mark = pad.image();
                dialog.dismiss();
                complete.accept(mark);
            });
        });
        dialog.show();
    }

    private static LinearLayout panel(ScanUi ui) {
        LinearLayout panel = ui.vertical();
        panel.setPadding(ui.dp(22), ui.dp(12), ui.dp(22), ui.dp(2));
        return panel;
    }

    private static final class SignaturePad extends View {
        private final Path strokes = new Path();
        private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int padding;
        private int activePointer = -1;
        private boolean hasInk;
        Consumer<Boolean> onInkChanged = ignored -> {};

        SignaturePad(Context context, ScanUi ui) {
            super(context);
            padding = ui.dp(8);
            setBackground(ui.panel(ScanUi.WHITE, 10, ScanUi.LINE));
            setClipToOutline(true);
            setContentDescription("签名书写区");
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            ink.setColor(0xff253d63);
            ink.setStyle(Paint.Style.STROKE);
            ink.setStrokeWidth(ui.dp(3));
            ink.setStrokeCap(Paint.Cap.ROUND);
            ink.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawPath(strokes, ink);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                activePointer = event.getPointerId(0);
                float x = x(event.getX());
                float y = y(event.getY());
                strokes.moveTo(x, y);
                strokes.lineTo(x + .01f, y);
                if (!hasInk) {
                    hasInk = true;
                    onInkChanged.accept(true);
                }
                invalidate();
                return true;
            }
            if (action == MotionEvent.ACTION_CANCEL) {
                activePointer = -1;
                return true;
            }
            int pointer = event.findPointerIndex(activePointer);
            if (pointer < 0) return true;
            boolean ending = action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_POINTER_UP && event.getActionIndex() == pointer;
            if (action == MotionEvent.ACTION_MOVE || ending) {
                for (int i = 0; i < event.getHistorySize(); i++) {
                    strokes.lineTo(x(event.getHistoricalX(pointer, i)), y(event.getHistoricalY(pointer, i)));
                }
                strokes.lineTo(x(event.getX(pointer)), y(event.getY(pointer)));
                invalidate();
            }
            if (ending) {
                activePointer = -1;
                performClick();
            }
            return true;
        }

        @Override public boolean performClick() {
            return super.performClick();
        }

        private float x(float x) { return Math.max(1, Math.min(x, getWidth() - 1)); }
        private float y(float y) { return Math.max(1, Math.min(y, getHeight() - 1)); }

        void clear() {
            activePointer = -1;
            hasInk = false;
            strokes.reset();
            onInkChanged.accept(false);
            invalidate();
        }

        Bitmap image() {
            RectF bounds = new RectF();
            strokes.computeBounds(bounds, true);
            int left = (int) Math.floor(bounds.left) - padding;
            int top = (int) Math.floor(bounds.top) - padding;
            int width = (int) Math.ceil(bounds.right) + padding - left;
            int height = (int) Math.ceil(bounds.bottom) + padding - top;
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            try {
                canvas.translate(-left, -top);
                canvas.drawPath(strokes, ink);
            } finally {
                canvas.setBitmap(null);
            }
            return bitmap;
        }
    }
}
