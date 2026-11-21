package com.scandesk.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

final class ScanUi {
    static final int WHITE = 0xfffbfaf7;
    static final int PAPER = 0xffffffff;
    static final int INK = 0xff292622;
    static final int SECONDARY = 0xff686159;
    static final int ACCENT = 0xff934d37;
    static final int CROP = 0xffa9543c;
    static final int ACCENT_SOFT = 0xfff4e8e1;
    static final int LINE = 0xffe9e5df;
    static final int BOARD = 0xffe9e8e5;
    static final int SURFACE = 0xfff2f0ec;
    static final int RED = 0xffb22734;

    private final Context context;

    ScanUi(Context context) {
        this.context = context;
    }

    int dp(float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    float px(float sp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp,
                context.getResources().getDisplayMetrics());
    }

    int match() { return ViewGroup.LayoutParams.MATCH_PARENT; }
    int wrap() { return ViewGroup.LayoutParams.WRAP_CONTENT; }

    LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    LinearLayout horizontal() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    TextView text(CharSequence label, int size, int color) {
        TextView view = new TextView(context);
        view.setText(label);
        view.setTextColor(color);
        view.setTextSize(size);
        view.setIncludeFontPadding(false);
        return view;
    }

    TextView heading(CharSequence label, int size) {
        TextView view = text(label, size, INK);
        view.setTypeface(Typeface.create(Typeface.DEFAULT, 600, false));
        return view;
    }

    TextView display(CharSequence label, int size) {
        TextView view = text(label, size, INK);
        view.setTextLocale(Locale.SIMPLIFIED_CHINESE);
        view.setTypeface(Typeface.SERIF);
        view.setLetterSpacing(.03f);
        return view;
    }

    Button button(String label, boolean primary, Runnable action) {
        Button button = new Button(context);
        button.setText(label);
        button.setAllCaps(false);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextSize(15);
        button.setTextColor(primary ? PAPER : INK);
        button.setIncludeFontPadding(false);
        button.setPadding(dp(14), dp(8), dp(14), dp(8));
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setMinWidth(dp(48));
        button.setMinimumWidth(dp(48));
        button.setGravity(Gravity.CENTER);
        button.setStateListAnimator(null);
        button.setBackground(ripple(primary ? INK : PAPER, 8, primary ? 0 : LINE));
        button.setOnClickListener(v -> action.run());
        return button;
    }

    Button quietButton(String label, int textColor, Runnable action) {
        Button button = button(label, false, action);
        button.setTextColor(textColor);
        button.setBackground(ripple(0, 6, 0));
        return button;
    }

    ImageButton icon(int drawable, String label, Runnable action) {
        ImageButton button = new ImageButton(context);
        button.setImageResource(drawable);
        button.setImageTintList(ColorStateList.valueOf(INK));
        button.setContentDescription(label);
        button.setBackground(ripple(0, 12, 0));
        button.setPadding(dp(11), dp(11), dp(11), dp(11));
        button.setScaleType(ImageView.ScaleType.FIT_CENTER);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    View tool(int icon, String label, boolean selected, Runnable action) {
        return tool(icon, label, selected, INK, action);
    }

    View tool(int icon, String label, boolean selected, int foreground, Runnable action) {
        int color = selected ? ACCENT : foreground;
        LinearLayout tool = vertical();
        tool.setGravity(Gravity.CENTER);
        tool.setPadding(dp(3), dp(5), dp(3), dp(5));
        tool.setBackground(ripple(selected ? ACCENT_SOFT : 0, 10, 0));
        tool.setSelected(selected);
        tool.setFocusable(true);
        tool.setContentDescription(label);
        tool.setOnClickListener(v -> action.run());
        ImageView image = new ImageView(context);
        image.setImageResource(icon);
        image.setImageTintList(ColorStateList.valueOf(color));
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        tool.addView(image, new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView text = text(label, 13, color);
        text.setPadding(0, dp(4), 0, 0);
        text.setMaxLines(1);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        tool.addView(text, new LinearLayout.LayoutParams(wrap(), wrap()));
        return tool;
    }

    View header(String title, Runnable backAction, String actionLabel, Runnable action) {
        LinearLayout toolbar = horizontal();
        toolbar.setPadding(dp(8), dp(5), dp(14), dp(5));
        toolbar.setBackgroundColor(WHITE);
        if (backAction != null) toolbar.addView(icon(R.drawable.ic_back, "返回", backAction),
                new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView heading = heading(title, 20);
        heading.setMaxLines(1);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        heading.setAccessibilityHeading(true);
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(0, wrap(), 1f);
        headingParams.setMarginStart(dp(backAction == null ? 12 : 4));
        headingParams.setMarginEnd(dp(8));
        toolbar.addView(heading, headingParams);
        if (action != null) toolbar.addView(button(actionLabel, true, action),
                new LinearLayout.LayoutParams(wrap(), wrap()));
        toolbar.setLayoutParams(new LinearLayout.LayoutParams(match(), wrap()));
        return toolbar;
    }

    View rule() {
        View view = new View(context);
        view.setBackgroundColor(LINE);
        view.setLayoutParams(new LinearLayout.LayoutParams(match(), dp(1)));
        return view;
    }

    GradientDrawable panel(int color, float radius, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        if (stroke != 0) shape.setStroke(dp(1), stroke);
        return shape;
    }

    RippleDrawable ripple(int color, float radius, int stroke) {
        int feedback = color == ACCENT || color == INK ? 0x33ffffff : 0x14934d37;
        return new RippleDrawable(ColorStateList.valueOf(feedback),
                panel(color, radius, stroke), panel(WHITE, radius, 0));
    }
}
