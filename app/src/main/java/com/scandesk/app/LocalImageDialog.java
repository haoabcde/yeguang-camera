package com.scandesk.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.function.BiConsumer;

final class LocalImageDialog {
    private LocalImageDialog() {}

    static void resize(Activity activity, ScanUi ui, BiConsumer<Integer, Integer> complete) {
        LinearLayout panel = panel(ui);
        TextView explanation = ui.text("按原比例适配输入的最大宽高", 14, ScanUi.SECONDARY);
        explanation.setPadding(0, 0, 0, ui.dp(14));
        panel.addView(explanation);
        LinearLayout row = ui.horizontal();
        EditText width = sizeField(activity, ui, row, "最大宽度（像素）", true);
        EditText height = sizeField(activity, ui, row, "最大高度（像素）", false);
        width.setNextFocusForwardId(height.getId());
        panel.addView(row, new LinearLayout.LayoutParams(ui.match(), ui.wrap()));
        panel.setFocusableInTouchMode(true);
        panel.requestFocus();

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("等比改尺寸")
                .setView(panel)
                .setNegativeButton("取消", null)
                .setPositiveButton("导出 PNG", null)
                .create();
        dialog.setOnShowListener(ignored ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                    int requestedWidth = size(width);
                    int requestedHeight = size(height);
                    if (requestedWidth == 0 || requestedHeight == 0) return;
                    dialog.dismiss();
                    complete.accept(requestedWidth, requestedHeight);
                }));
        dialog.show();
    }

    private static EditText sizeField(Activity activity, ScanUi ui, LinearLayout row, String label,
            boolean first) {
        LinearLayout field = ui.vertical();
        LinearLayout.LayoutParams fieldSize = new LinearLayout.LayoutParams(0, ui.wrap(), 1f);
        if (first) fieldSize.rightMargin = ui.dp(10);
        row.addView(field, fieldSize);
        TextView text = ui.text(label, 13, ScanUi.INK);
        EditText value = new EditText(activity);
        value.setId(View.generateViewId());
        value.setInputType(InputType.TYPE_CLASS_NUMBER);
        value.setImeOptions(first ? EditorInfo.IME_ACTION_NEXT : EditorInfo.IME_ACTION_DONE);
        value.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});
        value.setText("1600");
        value.setSelectAllOnFocus(true);
        text.setLabelFor(value.getId());
        field.addView(text);
        field.addView(value, new LinearLayout.LayoutParams(ui.match(), ui.wrap()));
        return value;
    }

    private static int size(EditText input) {
        try {
            int value = Integer.parseInt(input.getText().toString());
            if (value >= 1 && value <= LocalImages.RESIZE_EDGE_LIMIT) return value;
        } catch (NumberFormatException ignored) {
            // The field below reports the supported range.
        }
        input.setError("请输入 1–4096 之间的整数");
        return 0;
    }

    static void passport(Activity activity, ScanUi ui, BiConsumer<Boolean, Integer> complete) {
        LinearLayout panel = panel(ui);
        panel.addView(ui.heading("照片规格", 14));
        RadioGroup formats = new RadioGroup(activity);
        RadioButton one = photoSize(activity, ui, formats, "一寸 · 295 × 413 像素");
        RadioButton two = photoSize(activity, ui, formats, "二寸 · 413 × 579 像素");
        formats.check(one.getId());
        panel.addView(formats, new LinearLayout.LayoutParams(ui.match(), ui.wrap()));

        TextView backgroundLabel = ui.heading("背景颜色", 14);
        backgroundLabel.setPadding(0, ui.dp(12), 0, 0);
        panel.addView(backgroundLabel);
        Spinner background = new Spinner(activity, Spinner.MODE_DROPDOWN);
        background.setId(View.generateViewId());
        background.setMinimumHeight(ui.dp(48));
        background.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"保持原色", "白底", "蓝底", "红底"}));
        backgroundLabel.setLabelFor(background.getId());
        panel.addView(background, new LinearLayout.LayoutParams(ui.match(), ui.dp(48)));
        panel.addView(ui.text("换底色适用于背景均匀的纯色底照片", 13, ScanUi.SECONDARY));
        int[] colors = {LocalImages.KEEP_BACKGROUND, Color.WHITE, 0xff438edb, 0xffce302e};
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(panel, new ScrollView.LayoutParams(ui.match(), ui.wrap()));
        new AlertDialog.Builder(activity)
                .setTitle("证件照裁尺寸")
                .setView(scroll)
                .setNegativeButton("取消", null)
                .setPositiveButton("导出 PNG", (dialog, which) ->
                        complete.accept(formats.getCheckedRadioButtonId() == two.getId(),
                                colors[background.getSelectedItemPosition()]))
                .show();
    }

    private static RadioButton photoSize(Activity activity, ScanUi ui, RadioGroup group, String label) {
        RadioButton choice = new RadioButton(activity);
        choice.setId(View.generateViewId());
        choice.setText(label);
        choice.setTextSize(14);
        choice.setTextColor(ScanUi.INK);
        choice.setButtonTintList(new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}}, new int[]{ScanUi.ACCENT, ScanUi.SECONDARY}));
        choice.setMinHeight(ui.dp(48));
        group.addView(choice, new RadioGroup.LayoutParams(ui.match(), ui.wrap()));
        return choice;
    }

    private static LinearLayout panel(ScanUi ui) {
        LinearLayout panel = ui.vertical();
        panel.setPadding(ui.dp(22), ui.dp(8), ui.dp(22), ui.dp(8));
        return panel;
    }
}
