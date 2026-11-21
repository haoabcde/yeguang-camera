package com.scandesk.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

final class NumberReviewDialog {
    private final String[] lines;
    private final List<Integer> rows = new ArrayList<>();
    private final Map<String, ScanRecognition.NumberLine> locations = new HashMap<>();
    private final String[] original;
    private final Bitmap page;
    private final ScanPreview preview;
    private final EditText editor;
    private final TextView counter;
    private final android.view.View previous, next;
    private Bitmap strip;
    private int at;

    static void show(Activity activity, ScanUi ui, Bitmap page, String text,
            List<ScanRecognition.NumberLine> anchors, Consumer<String> save) {
        new NumberReviewDialog(activity, ui, page, text, anchors, save);
    }

    private NumberReviewDialog(Activity activity, ScanUi ui, Bitmap page, String text,
            List<ScanRecognition.NumberLine> anchors, Consumer<String> save) {
        this.page = page;
        lines = text.split("\n", -1);
        original = lines.clone();
        for (ScanRecognition.NumberLine anchor : anchors) {
            String key = anchor.reviewedText.trim();
            // Repeated values such as identical prices cannot identify a source row.
            if (locations.containsKey(key)) locations.put(key, null);
            else locations.put(key, anchor);
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < lines.length; i++) {
            if (!ScanRecognition.NumberLine.hasDigit(lines[i])) continue;
            rows.add(i);
            String key = lines[i].trim();
            if (!seen.add(key)) locations.remove(key);
        }
        LinearLayout panel = ui.vertical();
        panel.setPadding(ui.dp(16), ui.dp(6), ui.dp(16), 0);
        panel.setFocusableInTouchMode(true);
        LinearLayout nav = ui.horizontal();
        previous = ui.icon(R.drawable.ic_previous, "上一行数字", () -> move(-1));
        next = ui.icon(R.drawable.ic_next, "下一行数字", () -> move(1));
        counter = ui.text("", 14, ScanUi.SECONDARY);
        counter.setGravity(Gravity.CENTER);
        nav.addView(previous, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        nav.addView(counter, new LinearLayout.LayoutParams(0, ui.wrap(), 1));
        nav.addView(next, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        panel.addView(nav);
        preview = new ScanPreview(activity, ui);
        int previewDp = Math.min(150, activity.getResources().getConfiguration().screenHeightDp / 4);
        panel.addView(preview, new LinearLayout.LayoutParams(ui.match(), ui.dp(previewDp)));
        editor = new EditText(activity);
        editor.setSingleLine(true);
        editor.setTextColor(ScanUi.INK);
        editor.setTextSize(16);
        editor.setContentDescription("可更正的数字行");
        panel.addView(editor, new LinearLayout.LayoutParams(ui.match(), ui.wrap()));
        ScrollView body = new ScrollView(activity);
        body.addView(panel);
        if (rows.isEmpty()) {
            page.recycle();
            return;
        }
        display();
        panel.requestFocus();
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("核对数字").setView(body)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (d, w) -> {
                    lines[rows.get(at)] = editor.getText().toString();
                    for (int row : rows) {
                        ScanRecognition.NumberLine anchor = locations.get(original[row].trim());
                        if (anchor != null) anchor.reviewedText = lines[row];
                    }
                    save.accept(String.join("\n", lines));
                }).create();
        dialog.setOnDismissListener(d -> {
            preview.show(null, null, null);
            if (strip != null && strip != page) strip.recycle();
            page.recycle();
        });
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.show();
    }

    private void move(int direction) {
        lines[rows.get(at)] = editor.getText().toString();
        at += direction;
        display();
    }

    private void display() {
        int row = rows.get(at);
        ScanRecognition.NumberLine anchor = locations.get(original[row].trim());
        RectF rect = anchor == null ? null : anchor.bounds;
        Bitmap old = strip;
        strip = rect == null ? page : cut(rect);
        preview.show(strip, null, null);
        if (old != null && old != strip && old != page) old.recycle();
        counter.setText(counter.getResources().getString(rect == null ? R.string.number_page : R.string.number_region,
                at + 1, rows.size()));
        previous.setEnabled(at > 0);
        next.setEnabled(at + 1 < rows.size());
        previous.setAlpha(previous.isEnabled() ? 1f : .3f);
        next.setAlpha(next.isEnabled() ? 1f : .3f);
        editor.setText(lines[row]);
    }

    private Bitmap cut(RectF rect) {
        int width = page.getWidth(), height = page.getHeight();
        float pad = Math.max(4, rect.height() * height * .6f);
        int left = Math.max(0, Math.min(width - 1, (int) (rect.left * width - pad)));
        int top = Math.max(0, Math.min(height - 1, (int) (rect.top * height - pad)));
        int right = Math.max(left + 1, Math.min(width, (int) Math.ceil(rect.right * width + pad)));
        int bottom = Math.max(top + 1, Math.min(height, (int) Math.ceil(rect.bottom * height + pad)));
        return Bitmap.createBitmap(page, left, top, right - left, bottom - top);
    }
}
