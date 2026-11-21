package com.scandesk.app;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

@SuppressLint("ViewConstructor") // Constructed with the active document and controller.
final class EditorView extends LinearLayout {
    enum Action { BACK, EXPORT, AUTO_CROP, CROP, ROTATE, ENHANCE, COMPLETE, RESET, PAGE_MENU, ADD }

    interface Controller {
        void action(Action action);
        void select(ScanDocument.Page page);
        void move(String origin, String destination);
        void cropChanged(CropQuad crop);
        void load(ScanDocument.Page page, boolean original, boolean thumbnail, ImageResult result);
    }

    interface ImageResult {
        void loaded(Bitmap bitmap);
    }

    private final ScanUi ui;
    private final Controller controller;
    private final ScanDocument document;
    private final ScanDocument.Page page;
    private final boolean cropping;
    private CropQuad pendingCrop;
    private ScanPreview preview;
    private Bitmap shown;
    private FrameLayout imageFrame;
    private int imageRequest;
    private boolean comparingOriginal;

    EditorView(Context context, ScanUi ui, ScanDocument document, ScanDocument.Page page,
            CropQuad pendingCrop, boolean cropping, boolean landscape, Controller controller) {
        super(context);
        this.ui = ui;
        this.controller = controller;
        this.document = document;
        this.page = page;
        this.pendingCrop = pendingCrop;
        this.cropping = cropping;
        setOrientation(VERTICAL);
        setBackgroundColor(ScanUi.WHITE);
        addView(ui.header("编辑扫描件", () -> controller.action(Action.BACK), "导出",
                page == null ? null : () -> controller.action(Action.EXPORT)));
        View canvas = canvas();
        if (page == null) {
            addView(canvas, new LayoutParams(ui.match(), 0, 1));
            return;
        }
        LinearLayout controls = ui.vertical();
        controls.setBackgroundColor(ScanUi.PAPER);
        controls.addView(pageBar(), new LayoutParams(ui.match(), ui.wrap()));
        controls.addView(ui.rule());
        controls.addView(thumbnails(), new LayoutParams(ui.match(), ui.wrap()));
        controls.addView(ui.rule());
        controls.addView(tools(), new LayoutParams(ui.match(), ui.wrap()));
        if (landscape) {
            LinearLayout columns = ui.horizontal();
            columns.addView(canvas, new LayoutParams(0, ui.match(), 1));
            columns.addView(controls, new LayoutParams(ui.dp(342), ui.match()));
            addView(columns, new LayoutParams(ui.match(), 0, 1));
        } else {
            addView(canvas, new LayoutParams(ui.match(), 0, 1));
            addView(controls, new LayoutParams(ui.match(), ui.wrap()));
        }
    }

    private View canvas() {
        Context context = getContext();
        FrameLayout frame = new FrameLayout(context);
        frame.setBackgroundColor(ScanUi.BOARD);
        if (page == null) {
            frame.addView(ui.button("添加页面", true, () -> controller.action(Action.ADD)),
                    new FrameLayout.LayoutParams(ui.wrap(), ui.wrap(), Gravity.CENTER));
            return frame;
        }
        preview = new ScanPreview(context, ui);
        frame.addView(preview, new FrameLayout.LayoutParams(ui.match(), ui.match()));
        imageFrame = frame;
        loadPreview();
        return frame;
    }

    private void loadPreview() {
        int request = ++imageRequest;
        FrameLayout frame = imageFrame;
        while (frame.getChildCount() > 1) frame.removeViewAt(1);
        ProgressBar loading = new ProgressBar(getContext());
        loading.setIndeterminateTintList(ColorStateList.valueOf(ScanUi.ACCENT));
        loading.setContentDescription("正在读取图片");
        frame.addView(loading, new FrameLayout.LayoutParams(ui.dp(32), ui.dp(32), Gravity.CENTER));
        ScanDocument.Page display = comparingOriginal ? page.filter(ScanDocument.Filter.ORIGINAL) : page;
        controller.load(display, cropping, false, bitmap -> {
            if (request != imageRequest) return;
            frame.removeView(loading);
            if (bitmap != null) {
                preview.show(bitmap, cropping ? pendingCrop : null, controller::cropChanged,
                        shown != null && !cropping);
                shown = bitmap;
            } else {
                frame.addView(ui.button("无法读取原图，重试", false, () -> controller.select(page)),
                        new FrameLayout.LayoutParams(ui.wrap(), ui.wrap(), Gravity.CENTER));
            }
        });
    }

    void showCrop(CropQuad crop) {
        pendingCrop = crop;
        if (preview != null && shown != null) preview.show(shown, crop, controller::cropChanged);
    }

    private View pageBar() {
        Context context = getContext();
        LinearLayout bar = ui.horizontal();
        bar.setPadding(ui.dp(12), 0, ui.dp(12), 0);
        int number = document.pages.indexOf(page) + 1;
        TextView count = ui.text("第 " + number + " / " + document.pages.size() + " 页", 13, ScanUi.SECONDARY);
        count.setGravity(Gravity.CENTER);
        count.setMinHeight(ui.dp(48));
        count.setPadding(ui.dp(10), ui.dp(3), ui.dp(10), ui.dp(3));
        count.setFocusable(true);
        count.setContentDescription("第 " + number + " 页，共 " + document.pages.size() + " 页，页面操作");
        count.setBackground(ui.ripple(0, 10, 0));
        count.setOnClickListener(v -> controller.action(Action.PAGE_MENU));
        FrameLayout left = new FrameLayout(context);
        if (cropping) {
            android.widget.Button reset = ui.button("重置", false, () -> controller.action(Action.RESET));
            reset.setTextColor(ScanUi.SECONDARY);
            reset.setTextSize(13);
            reset.setBackground(ui.ripple(0, 10, 0));
            left.addView(reset, new FrameLayout.LayoutParams(ui.wrap(), ui.wrap(),
                    Gravity.START | Gravity.CENTER_VERTICAL));
        } else if (page.filter != ScanDocument.Filter.ORIGINAL) {
            android.widget.Button compare = ui.button("对比原图", false, () -> {});
            compare.setTextColor(ScanUi.SECONDARY);
            compare.setTextSize(13);
            compare.setPadding(ui.dp(8), 0, ui.dp(8), 0);
            compare.setBackground(ui.ripple(0, 10, 0));
            compare.setOnClickListener(v -> {
                comparingOriginal = !comparingOriginal;
                compare.setText(comparingOriginal ? "返回效果" : "对比原图");
                compare.setSelected(comparingOriginal);
                loadPreview();
            });
            left.addView(compare, new FrameLayout.LayoutParams(ui.wrap(), ui.wrap(),
                    Gravity.START | Gravity.CENTER_VERTICAL));
        }
        bar.addView(left, new LayoutParams(0, ui.wrap(), 1f));
        bar.addView(count, new LayoutParams(ui.wrap(), ui.wrap()));
        FrameLayout right = new FrameLayout(context);
        android.widget.Button crop = ui.button(cropping ? "自动裁边" : "页操作", false,
                () -> controller.action(cropping ? Action.AUTO_CROP : Action.PAGE_MENU));
        crop.setTextSize(13);
        crop.setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6));
        crop.setTextColor(cropping ? ScanUi.ACCENT : ScanUi.INK);
        crop.setBackground(ui.ripple(0, 10, 0));
        right.addView(crop, new FrameLayout.LayoutParams(ui.wrap(), ui.wrap(), Gravity.END | Gravity.CENTER_VERTICAL));
        bar.addView(right, new LayoutParams(0, ui.wrap(), 1f));
        return bar;
    }

    private View thumbnails() {
        HorizontalScrollView scroller = new HorizontalScrollView(getContext());
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setClipToPadding(false);
        scroller.setContentDescription("页面缩略图，长按拖动调整页序");
        LinearLayout row = ui.horizontal();
        row.setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8));
        scroller.addView(row, new HorizontalScrollView.LayoutParams(ui.wrap(), ui.wrap()));
        View selectedChild = null;
        for (int i = 0; i < document.pages.size(); i++) {
            ScanDocument.Page item = document.pages.get(i);
            View thumb = thumbnail(item, i + 1);
            row.addView(thumb, cardParams());
            if (item.id.equals(page.id)) selectedChild = thumb;
        }
        View add = ui.tool(R.drawable.ic_add, "添加页面", false, () -> controller.action(Action.ADD));
        add.setBackground(ui.ripple(ScanUi.WHITE, 8, ScanUi.LINE));
        LayoutParams addParams = cardParams();
        addParams.width = Math.max(ui.dp(78), Math.round(4 * ui.px(13)) + ui.dp(12));
        row.addView(add, addParams);
        View active = selectedChild;
        if (active != null) scroller.post(() -> {
            int desired = active.getLeft() - (scroller.getWidth() - active.getWidth()) / 2;
            scroller.scrollTo(Math.max(0, desired), 0);
        });
        return scroller;
    }

    private LayoutParams cardParams() {
        LayoutParams params = new LayoutParams(ui.dp(63), ui.dp(78));
        params.setMarginEnd(ui.dp(8));
        return params;
    }

    private View thumbnail(ScanDocument.Page item, int number) {
        Context context = getContext();
        boolean selected = page.id.equals(item.id);
        FrameLayout card = new FrameLayout(context);
        card.setBackground(ui.ripple(selected ? ScanUi.ACCENT_SOFT : ScanUi.SURFACE, 7,
                selected ? ScanUi.CROP : ScanUi.LINE));
        card.setClipToOutline(true);
        card.setPadding(ui.dp(2), ui.dp(2), ui.dp(2), ui.dp(2));
        card.setFocusable(true);
        card.setSelected(selected);
        card.setContentDescription("第 " + number + " 页" + (selected ? "，当前页" : ""));
        card.setOnClickListener(v -> controller.select(item));
        card.setOnLongClickListener(v -> v.startDragAndDrop(
                ClipData.newPlainText("页面", ""), new View.DragShadowBuilder(v), item.id, 0));
        card.setOnDragListener((v, event) -> {
            Object origin = event.getLocalState();
            if (!(origin instanceof String)) return false;
            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_ENTERED:
                    card.setAlpha(.70f);
                    break;
                case DragEvent.ACTION_DRAG_EXITED:
                case DragEvent.ACTION_DRAG_ENDED:
                    card.setAlpha(1f);
                    break;
                case DragEvent.ACTION_DROP:
                    card.setAlpha(1f);
                    controller.move((String) origin, item.id);
                    break;
            }
            return true;
        });

        ImageView image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(image, new FrameLayout.LayoutParams(ui.match(), ui.match()));
        TextView label = ui.text(Integer.toString(number), 11, selected ? ScanUi.PAPER : ScanUi.INK);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label.setGravity(Gravity.CENTER);
        label.setBackground(ui.panel(selected ? ScanUi.ACCENT : 0xf5ffffff, 6, 0));
        label.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams labelParams = new FrameLayout.LayoutParams(ui.dp(21), ui.dp(17),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        labelParams.bottomMargin = ui.dp(3);
        card.addView(label, labelParams);
        controller.load(item, false, true, bitmap -> {
            if (bitmap != null) image.setImageBitmap(bitmap);
        });
        return card;
    }

    private View tools() {
        LinearLayout bar = ui.horizontal();
        bar.setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6));
        bar.addView(ui.tool(R.drawable.ic_crop, "裁切", cropping, () -> controller.action(Action.CROP)),
                new LayoutParams(0, ui.wrap(), 1f));
        bar.addView(ui.tool(R.drawable.ic_rotate, "旋转", false, () -> controller.action(Action.ROTATE)),
                new LayoutParams(0, ui.wrap(), 1f));
        bar.addView(ui.tool(R.drawable.ic_enhance, "增强",
                !cropping && page.filter != ScanDocument.Filter.ORIGINAL, () -> controller.action(Action.ENHANCE)),
                new LayoutParams(0, ui.wrap(), 1f));
        View divider = new View(getContext());
        divider.setBackgroundColor(ScanUi.LINE);
        LayoutParams dividerParams = new LayoutParams(ui.dp(1), ui.dp(28));
        dividerParams.setMargins(ui.dp(6), 0, ui.dp(10), 0);
        bar.addView(divider, dividerParams);
        bar.addView(ui.button("完成", true, () -> controller.action(Action.COMPLETE)),
                new LayoutParams(ui.dp(76), ui.wrap()));
        return bar;
    }
}
