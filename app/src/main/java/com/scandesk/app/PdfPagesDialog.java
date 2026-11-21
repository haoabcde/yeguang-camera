package com.scandesk.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.text.InputFilter;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PdfPagesDialog {
    private static final Pattern PAGE_RANGE = Pattern.compile("([0-9]{1,3})(?:\\s*-\\s*([0-9]{1,3}))?");

    interface Apply {
        boolean save(List<PdfWorkspace.Page> pages, int position);
    }

    static void show(Activity activity, ScanUi ui, List<PdfWorkspace.Page> source,
            int position, String applyLabel, boolean requireSelection, Apply apply) {
        List<PdfWorkspace.Page> pages = new ArrayList<>(source);
        LinearLayout panel = ui.vertical();
        panel.setPadding(ui.dp(12), ui.dp(5), ui.dp(12), 0);
        Rows rows = new Rows(activity, ui, pages, position);
        LinearLayout actions = ui.horizontal();
        actions.addView(ui.button("按页码选", false, () -> rangeDialog(activity, ui, rows)));
        LinearLayout.LayoutParams selectLayout = new LinearLayout.LayoutParams(ui.wrap(), ui.wrap());
        selectLayout.setMarginStart(ui.dp(8));
        actions.addView(ui.button("全选", false, () -> {
            BitSet all = new BitSet(pages.size());
            all.set(0, pages.size());
            rows.select(all);
        }), selectLayout);
        panel.addView(actions, new LinearLayout.LayoutParams(ui.match(), ui.wrap()));
        TextView selectedInfo = ui.text("", 13, ScanUi.SECONDARY);
        selectedInfo.setPadding(ui.dp(6), ui.dp(8), ui.dp(6), 0);
        panel.addView(selectedInfo);
        ListView list = new ListView(activity);
        list.setAdapter(rows);
        int height = activity.getResources().getConfiguration().screenHeightDp;
        panel.addView(list, new LinearLayout.LayoutParams(ui.match(), ui.dp(Math.max(120,
                Math.min(420, height - 285)))));
        if (position > 0) list.setSelection(Math.max(0, position - 1));
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("选页与排序").setView(panel)
                .setNegativeButton("取消", null).setPositiveButton(applyLabel, null).create();
        dialog.setOnShowListener(ignored -> {
            Button confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            rows.selectionChanged = () -> {
                int count = 0;
                for (PdfWorkspace.Page page : pages) if (page.selected) count++;
                selectedInfo.setText("已选 " + count + " / " + pages.size() + " 页");
                confirm.setEnabled(!requireSelection || count > 0);
            };
            rows.selectionChanged.run();
            confirm.setOnClickListener(view -> {
                if (apply.save(pages, rows.position)) dialog.dismiss();
            });
        });
        dialog.show();
    }

    private static void rangeDialog(Activity activity, ScanUi ui, Rows rows) {
        LinearLayout panel = ui.vertical();
        panel.setPadding(ui.dp(22), ui.dp(8), ui.dp(22), 0);
        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setHint("例如 1-3, 6");
        input.setContentDescription("选择要导出的页码");
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(800)});
        panel.addView(input, new LinearLayout.LayoutParams(ui.match(), ui.wrap()));
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("按页码选择")
                .setView(panel).setNegativeButton("取消", null).setPositiveButton("选择", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    try {
                        rows.select(parse(input.getText().toString(), rows.pages.size()));
                        dialog.dismiss();
                    } catch (IllegalArgumentException invalid) {
                        input.setError("请输入 1 到 " + rows.pages.size() + " 之间的页码");
                    }
                }));
        dialog.show();
    }

    static BitSet parse(String text, int size) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replace("、", ",").replace('–', '-').replace('—', '-');
        BitSet selected = new BitSet(size);
        for (String part : normalized.split(",", -1)) {
            Matcher range = PAGE_RANGE.matcher(part.trim());
            if (!range.matches()) throw new IllegalArgumentException();
            int start = Integer.parseInt(range.group(1));
            int end = range.group(2) == null ? start : Integer.parseInt(range.group(2));
            if (start < 1 || end < start || end > size) throw new IllegalArgumentException();
            selected.set(start - 1, end);
        }
        return selected;
    }

    private static final class Rows extends BaseAdapter {
        final Activity activity;
        final ScanUi ui;
        final List<PdfWorkspace.Page> pages;
        Runnable selectionChanged = () -> {};
        int position;

        Rows(Activity activity, ScanUi ui, List<PdfWorkspace.Page> pages, int position) {
            this.activity = activity;
            this.ui = ui;
            this.pages = pages;
            this.position = position;
        }

        void select(BitSet selection) {
            for (int i = 0; i < pages.size(); i++) pages.set(i, pages.get(i).select(selection.get(i)));
            notifyDataSetChanged();
            selectionChanged.run();
        }

        @Override public int getCount() { return pages.size(); }
        @Override public Object getItem(int index) { return pages.get(index); }
        @Override public long getItemId(int index) { return index; }

        @Override public View getView(int index, View previous, ViewGroup parent) {
            PdfWorkspace.Page page = pages.get(index);
            LinearLayout row = ui.horizontal();
            row.setMinimumHeight(ui.dp(66));
            CheckBox include = new CheckBox(activity);
            include.setButtonTintList(ColorStateList.valueOf(ScanUi.ACCENT));
            include.setChecked(page.selected);
            include.setContentDescription("导出第 " + (index + 1) + " 页");
            include.setOnCheckedChangeListener((view, checked) -> {
                pages.set(index, pages.get(index).select(checked));
                selectionChanged.run();
            });
            row.addView(include, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(52)));
            LinearLayout labels = ui.vertical();
            TextView title = ui.heading("第 " + (index + 1) + " 页", 14);
            TextView subtitle = ui.text(page.name + " · 原第 " + (page.index + 1) + " 页", 12, ScanUi.SECONDARY);
            subtitle.setMaxLines(1);
            subtitle.setEllipsize(TextUtils.TruncateAt.END);
            title.setPadding(0, 0, 0, ui.dp(4));
            labels.addView(title);
            labels.addView(subtitle);
            labels.setOnClickListener(v -> {
                position = index;
                notifyDataSetChanged();
            });
            labels.setContentDescription("将第 " + (index + 1) + " 页设为当前页");
            labels.setSelected(position == index);
            title.setTextColor(position == index ? ScanUi.ACCENT : ScanUi.INK);
            row.addView(labels, new LinearLayout.LayoutParams(0, ui.wrap(), 1));
            moveButton(row, index, -1, R.drawable.ic_up, "上移第 ");
            moveButton(row, index, 1, R.drawable.ic_down, "下移第 ");
            return row;
        }

        private void moveButton(LinearLayout row, int index, int direction, int icon, String label) {
            int target = index + direction;
            boolean valid = target >= 0 && target < pages.size();
            View button = ui.icon(icon, label + (index + 1) + " 页", () -> {
                Collections.swap(pages, index, target);
                if (position == index) position = target;
                else if (position == target) position = index;
                notifyDataSetChanged();
            });
            button.setEnabled(valid);
            button.setAlpha(valid ? 1f : .28f);
            row.addView(button, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        }
    }
}
