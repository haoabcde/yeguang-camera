package com.scandesk.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

final class ToolShelf {
    private final Context context;
    private final ScanUi ui;
    private final LinearLayout layout;
    private final LinearLayout categories;
    private final LinearLayout content;
    private final ScrollView scroll;
    private final List<Button> tabs = new ArrayList<>();
    private final List<View> groups = new ArrayList<>();
    private final int columns;
    private int selected = -1;

    ToolShelf(Context context, ScanUi ui, Runnable back) {
        this.context = context;
        this.ui = ui;
        int width = context.getResources().getConfiguration().screenWidthDp;
        float font = context.getResources().getConfiguration().fontScale;
        columns = width >= 640 && font < 1.5f ? 3 : width >= 360 && font < 1.5f ? 2 : 1;
        layout = ui.vertical();
        layout.addView(ui.header("全部工具", back, null, null));
        categories = ui.horizontal();
        categories.setPadding(ui.dp(20), 0, ui.dp(20), 0);
        layout.addView(categories, new LinearLayout.LayoutParams(ui.match(), ui.wrap()));
        layout.addView(ui.rule());
        scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        content = ui.vertical();
        content.setPadding(ui.dp(24), ui.dp(1), ui.dp(24), ui.dp(24));
        scroll.addView(content, new ScrollView.LayoutParams(width >= 720 ? ui.dp(688) : ui.match(),
                ui.wrap(), Gravity.CENTER_HORIZONTAL));
        layout.addView(scroll, new LinearLayout.LayoutParams(ui.match(), 0, 1));
    }

    void section(String tabLabel, String name, Item... items) {
        LinearLayout group = ui.vertical();
        group.setPadding(0, ui.dp(22), 0, ui.dp(8));
        TextView label = ui.heading(name, 19);
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

        group.addView(ui.rule());
        for (int start = 0; start < items.length; start += columns) {
            LinearLayout row = ui.horizontal();
            for (int index = start; index < Math.min(start + columns, items.length); index++) {
                row.addView(tile(items[index]), new LinearLayout.LayoutParams(0, ui.wrap(), 1));
            }
            group.addView(row, new LinearLayout.LayoutParams(ui.match(), ui.wrap()));
            group.addView(ui.rule());
        }
        content.addView(group, new LinearLayout.LayoutParams(ui.match(), ui.wrap()));
    }

    int offset() { return scroll.getScrollY(); }

    View view(int offset) {
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
        LinearLayout tile = ui.horizontal();
        tile.setMinimumHeight(ui.dp(52));
        tile.setPadding(ui.dp(2), ui.dp(8), ui.dp(8), ui.dp(8));
        tile.setBackground(ui.ripple(0, 6, 0));
        tile.setContentDescription(item.label);
        tile.setFocusable(true);
        tile.setOnClickListener(v -> item.action.run());
        ImageView icon = new ImageView(context);
        icon.setImageResource(item.icon);
        icon.setImageTintList(ColorStateList.valueOf(ScanUi.SECONDARY));
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20));
        iconParams.setMarginEnd(ui.dp(11));
        tile.addView(icon, iconParams);
        TextView name = ui.text(item.label, 14, ScanUi.INK);
        tile.addView(name, new LinearLayout.LayoutParams(0, ui.wrap(), 1));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
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
        final Runnable action;

        Item(String label, int icon, Runnable action) {
            this.label = label;
            this.icon = icon;
            this.action = action;
        }
    }
}
