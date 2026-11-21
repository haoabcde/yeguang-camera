package com.scandesk.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

final class ToolShelf {
    private final Context context;
    private final ScanUi ui;
    private final LinearLayout layout;
    private final LinearLayout categories;
    private final LinearLayout content;
    private final ScrollView scroll;
    private final List<Button> tabs = new ArrayList<>();
    private final List<View> groups = new ArrayList<>();
    private final List<List<LinearLayout>> rows = new ArrayList<>();
    private final List<List<View>> separators = new ArrayList<>();
    private final List<List<Item>> itemsByGroup = new ArrayList<>();
    private TextView empty;
    private EditText search;
    private final int columns;
    private int selected = -1;

    ToolShelf(Context context, ScanUi ui, Runnable back) {
        this.context = context;
        this.ui = ui;
        int width = context.getResources().getConfiguration().screenWidthDp;
        float font = context.getResources().getConfiguration().fontScale;
        columns = width >= 640 && font < 1.5f ? 4 : width >= 360 && font < 1.5f ? 3
                : width >= 360 ? 2 : 1;
        layout = ui.vertical();
        layout.addView(ui.header("工具", back, null, null));
        layout.setFocusableInTouchMode(true);
        categories = ui.horizontal();
        categories.setPadding(ui.dp(20), 0, ui.dp(20), 0);
        layout.addView(categories, new LinearLayout.LayoutParams(ui.match(), ui.wrap()));
        scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        content = ui.vertical();
        content.setPadding(ui.dp(20), ui.dp(2), ui.dp(20), ui.dp(24));
        scroll.addView(content, new ScrollView.LayoutParams(width >= 720 ? ui.dp(688) : ui.match(),
                ui.wrap(), Gravity.CENTER_HORIZONTAL));
        layout.addView(scroll, new LinearLayout.LayoutParams(ui.match(), 0, 1));
    }

    void search(String value, Consumer<String> changed) {
        search = new EditText(context);
        search.setSingleLine();
        search.setHint("搜索工具，比如去阴影");
        search.setTextSize(13);
        search.setTextColor(ScanUi.INK);
        search.setHintTextColor(ScanUi.SECONDARY);
        search.setMinHeight(ui.dp(48));
        search.setPadding(ui.dp(13), 0, ui.dp(13), 0);
        search.setBackground(ui.panel(ScanUi.SURFACE, 14, 0));
        search.setText(value);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ui.match(), ui.wrap());
        params.setMargins(ui.dp(20), ui.dp(3), ui.dp(20), ui.dp(6));
        layout.addView(search, 1, params);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                changed.accept(text.toString());
                filter(text.toString());
            }
            @Override public void afterTextChanged(Editable text) {}
        });
    }

    private void filter(String query) {
        String term = normalize(query);
        int total = 0;
        for (int section = 0; section < groups.size(); section++) {
            List<Item> items = itemsByGroup.get(section);
            List<LinearLayout> groupRows = rows.get(section);
            List<View> rules = separators.get(section);
            int count = rebuildRows(items, groupRows, rules, term);
            total += count;
            groups.get(section).setVisibility(count > 0 ? View.VISIBLE : View.GONE);
            tabs.get(section).setVisibility(count > 0 ? View.VISIBLE : View.GONE);
        }
        if (empty != null) empty.setVisibility(total == 0 ? View.VISIBLE : View.GONE);
        categories.setVisibility(term.isEmpty() ? View.VISIBLE : View.GONE);
        if (!term.isEmpty()) scroll.scrollTo(0, 0);
    }

    private int rebuildRows(List<Item> items, List<LinearLayout> groupRows, List<View> rules, String term) {
        int count = 0;
        for (LinearLayout row : groupRows) row.removeAllViews();
        for (Item item : items) {
            if (!normalize(item.label + item.detail + item.aliases).contains(term)) continue;
            LinearLayout.LayoutParams slot = new LinearLayout.LayoutParams(0, ui.match(), 1);
            slot.setMarginEnd(ui.dp(6));
            groupRows.get(count / columns).addView(tile(item), slot);
            count++;
        }
        for (int i = 0; i < groupRows.size(); i++) {
            int visibility = i * columns < count ? View.VISIBLE : View.GONE;
            groupRows.get(i).setVisibility(visibility);
            rules.get(i).setVisibility(visibility);
        }
        return count;
    }

    private static String normalize(String value) {
        return value.replace(" ", "").trim().toLowerCase(Locale.ROOT);
    }

    void section(String tabLabel, String name, Item... items) {
        LinearLayout group = ui.vertical();
        group.setPadding(0, ui.dp(20), 0, ui.dp(8));
        TextView label = ui.heading(name, 16);
        label.setAccessibilityHeading(true);
        label.setPadding(0, 0, 0, ui.dp(12));
        group.addView(label);

        int sectionIndex = groups.size();
        Button tab = ui.quietButton(tabLabel, ScanUi.SECONDARY, () -> {
            scroll.scrollTo(0, group.getTop());
            select(sectionIndex);
        });
        tab.setTextSize(14);
        tab.setPadding(ui.dp(3), 0, ui.dp(3), 0);
        tab.setContentDescription(name);
        categories.addView(tab, new LinearLayout.LayoutParams(0, ui.wrap(), 1));
        groups.add(group);
        tabs.add(tab);

        List<LinearLayout> groupRows = new ArrayList<>();
        List<View> groupRules = new ArrayList<>();
        for (int start = 0; start < items.length; start += columns) {
            LinearLayout row = ui.horizontal();
            View divider = ui.rule();
            divider.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            divider.setLayoutParams(new LinearLayout.LayoutParams(ui.match(), ui.dp(8)));
            group.addView(row, new LinearLayout.LayoutParams(ui.match(), ui.wrap()));
            group.addView(divider);
            groupRows.add(row);
            groupRules.add(divider);
        }
        rows.add(groupRows);
        separators.add(groupRules);
        itemsByGroup.add(java.util.Arrays.asList(items));
        content.addView(group, new LinearLayout.LayoutParams(ui.match(), ui.wrap()));
    }

    int offset() { return scroll.getScrollY(); }

    View view(int offset) {
        empty = ui.text("没有匹配的工具", 14, ScanUi.SECONDARY);
        empty.setPadding(0, ui.dp(16), 0, 0);
        content.addView(empty);
        filter(search == null ? "" : search.getText().toString());
        layout.requestFocus();
        select(0);
        scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> {
            int visible = 0;
            for (int i = 1; i < groups.size(); i++) {
                if (groups.get(i).getTop() > y + ui.dp(26)) break;
                visible = i;
            }
            if (!scroll.canScrollVertically(1) && y > 0) visible = groups.size() - 1;
            select(visible);
        });
        if (offset > 0) scroll.post(() -> scroll.scrollTo(0, offset));
        return layout;
    }

    private View tile(Item item) {
        LinearLayout tile = ui.vertical();
        tile.setMinimumHeight(ui.dp(112));
        tile.setPadding(ui.dp(8), ui.dp(12), ui.dp(6), ui.dp(12));
        tile.setBackground(ui.ripple(0, 14, 0));
        tile.setContentDescription(item.label);
        tile.setFocusable(true);
        tile.setOnClickListener(v -> item.action.run());
        ImageView icon = new ImageView(context);
        icon.setImageResource(item.icon);
        icon.setImageTintList(ColorStateList.valueOf(ScanUi.INK));
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(ui.dp(23), ui.dp(23));
        iconParams.bottomMargin = ui.dp(12);
        tile.addView(icon, iconParams);
        LinearLayout labels = ui.vertical();
        TextView name = ui.text(item.label, 12, ScanUi.INK);
        name.setMaxLines(2);
        labels.addView(name);
        if (!item.detail.isEmpty()) {
            TextView detail = ui.text(item.detail, 11, ScanUi.SECONDARY);
            detail.setPadding(0, ui.dp(5), 0, 0);
            detail.setMaxLines(2);
            detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
            labels.addView(detail);
        }
        labels.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        tile.setContentDescription(item.label + (item.detail.isEmpty() ? "" : "，" + item.detail));
        tile.addView(labels, new LinearLayout.LayoutParams(ui.match(), ui.wrap()));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return tile;
    }

    private void select(int index) {
        if (selected == index || index < 0 || index >= tabs.size()) return;
        selected = index;
        for (int i = 0; i < tabs.size(); i++) {
            Button tab = tabs.get(i);
            boolean active = i == index;
            tab.setSelected(active);
            tab.setTextColor(active ? ScanUi.ACCENT : ScanUi.SECONDARY);
            Drawable background = ui.ripple(0, 8, 0);
            if (active) {
                LayerDrawable underline = new LayerDrawable(new Drawable[]{
                        background, ui.panel(ScanUi.ACCENT, 1, 0)});
                underline.setLayerWidth(1, ui.dp(24));
                underline.setLayerHeight(1, ui.dp(2));
                underline.setLayerGravity(1, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
                background = underline;
            }
            tab.setBackground(background);
        }
    }

    static final class Item {
        final String label;
        final int icon;
        final String detail;
        final String aliases;
        final Runnable action;

        Item(String label, int icon, String detail, String aliases, Runnable action) {
            this.label = label;
            this.icon = icon;
            this.action = action;
            this.detail = detail;
            this.aliases = aliases;
        }
    }
}
