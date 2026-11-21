package com.scandesk.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

final class ScanSheet {
    private final Activity activity;
    private final ScanUi ui;
    private final Dialog dialog;
    private final LinearLayout body;

    ScanSheet(Activity activity, ScanUi ui, String title) {
        this.activity = activity;
        this.ui = ui;
        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        body = ui.vertical();
        body.setBackground(ui.panel(ScanUi.WHITE, 22, 0));
        body.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(19));
        LinearLayout heading = ui.horizontal();
        TextView label = ui.heading(title, 18);
        label.setPadding(ui.dp(8), ui.dp(8), 0, ui.dp(8));
        label.setAccessibilityHeading(true);
        heading.addView(label, new LinearLayout.LayoutParams(0, ui.wrap(), 1));
        heading.addView(ui.icon(R.drawable.ic_close, "关闭", dialog::dismiss),
                new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        body.addView(heading, new LinearLayout.LayoutParams(ui.match(), ui.wrap()));
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.addView(body, new ScrollView.LayoutParams(ui.match(), ui.wrap()));
        dialog.setContentView(scroll);
    }

    void detail(String message) {
        TextView label = ui.text(message, 12, ScanUi.SECONDARY);
        label.setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(12));
        label.setLineSpacing(ui.dp(3), 1);
        body.addView(label, new LinearLayout.LayoutParams(ui.match(), ui.wrap()));
    }

    void add(View view) {
        body.addView(view, new LinearLayout.LayoutParams(ui.match(), ui.wrap()));
    }

    void option(int icon, String label, String description, boolean selected, Runnable action) {
        LinearLayout row = ui.horizontal();
        row.setMinimumHeight(ui.dp(56));
        row.setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8));
        row.setBackground(ui.ripple(selected ? ScanUi.ACCENT_SOFT : ScanUi.WHITE, 10, 0));
        row.setFocusable(true);
        row.setSelected(selected);
        row.setOnClickListener(v -> {
            dialog.dismiss();
            action.run();
        });
        row.setContentDescription(description == null ? label : label + "，" + description);
        if (icon != 0) {
            ImageView image = new ImageView(activity);
            image.setImageResource(icon);
            image.setImageTintList(ColorStateList.valueOf(selected ? ScanUi.ACCENT : ScanUi.INK));
            image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams margin = new LinearLayout.LayoutParams(ui.dp(23), ui.dp(23));
            margin.setMarginEnd(ui.dp(13));
            row.addView(image, margin);
        }
        LinearLayout labels = ui.vertical();
        labels.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        labels.addView(ui.text(label, 14, selected ? ScanUi.ACCENT : ScanUi.INK));
        if (description != null) {
            TextView hint = ui.text(description, 12, ScanUi.SECONDARY);
            hint.setPadding(0, ui.dp(5), 0, 0);
            labels.addView(hint);
        }
        row.addView(labels, new LinearLayout.LayoutParams(0, ui.wrap(), 1));
        body.addView(row, new LinearLayout.LayoutParams(ui.match(), ui.wrap()));
    }

    void show() {
        if (activity.isFinishing()) return;
        dialog.show();
        Window window = dialog.getWindow();
        if (window == null) return;
        int width = activity.getResources().getDisplayMetrics().widthPixels;
        window.setLayout(Math.min(width - ui.dp(12), ui.dp(500)), WindowManager.LayoutParams.WRAP_CONTENT);
        window.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setDimAmount(.32f);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }
}
