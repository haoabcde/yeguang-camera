package com.scandesk.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

@android.annotation.SuppressLint("ViewConstructor") // Constructed by the Activity with its search actions.
final class LibrarySearchView extends LinearLayout {
    interface Actions {
        void search(String query, boolean index);
        void open(DocumentSearch.Hit hit);
    }

    final EditText input;
    private final ScanUi ui;
    private final Actions actions;
    private final LinearLayout results;

    LibrarySearchView(Context context, ScanUi ui, String query, Runnable back, Actions actions) {
        super(context);
        this.ui = ui;
        this.actions = actions;
        setOrientation(VERTICAL);
        setBackgroundColor(ScanUi.WHITE);
        addView(ui.header("搜索扫描件", back, null, null));
        LinearLayout field = ui.horizontal();
        field.setPadding(ui.dp(18), ui.dp(10), ui.dp(16), ui.dp(10));
        input = new EditText(context);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setHint("文件名、正文或编号");
        input.setTextSize(16);
        input.setTextColor(ScanUi.INK);
        input.setHintTextColor(ScanUi.SECONDARY);
        input.setBackgroundTintList(ColorStateList.valueOf(ScanUi.ACCENT));
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(120)});
        input.setText(query);
        input.setSelection(input.length());
        field.addView(input, new LayoutParams(0, ui.wrap(), 1));
        field.addView(ui.quietButton("搜索", ScanUi.ACCENT, () -> submit(false)));
        input.setOnEditorActionListener((v, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            submit(false);
            return true;
        });
        addView(field);
        results = ui.vertical();
        results.setPadding(ui.dp(22), 0, ui.dp(22), 0);
        addView(results, new LayoutParams(ui.match(), 0, 1));
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                results.removeAllViews();
            }
            @Override public void afterTextChanged(Editable text) {}
        });
    }

    private void submit(boolean index) {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) {
            results.removeAllViews();
            input.setError("请输入关键词");
            return;
        }
        InputMethodManager ime = getContext().getSystemService(InputMethodManager.class);
        if (ime != null) ime.hideSoftInputFromWindow(input.getWindowToken(), 0);
        actions.search(text, index);
    }

    void display(String query, DocumentSearch.Results found) {
        results.removeAllViews();
        TextView status = ui.text(found.hits.isEmpty() ? "没有找到“" + query + "”"
                : "“" + query + "”的结果 · " + found.hits.size() + " 条", 14, ScanUi.SECONDARY);
        status.setPadding(0, ui.dp(9), 0, ui.dp(9));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        results.addView(status);
        if (found.missingPages > 0) {
            Button index = ui.button("识别剩余 " + found.missingPages + " 页并搜索", false, () -> submit(true));
            results.addView(index);
        }
        if (found.unreadableDocuments > 0) {
            results.addView(ui.text(found.unreadableDocuments + " 份扫描件暂时无法读取", 13, ScanUi.SECONDARY));
        }
        ListView list = new ListView(getContext());
        list.setAdapter(new BaseAdapter() {
            public int getCount() { return found.hits.size(); }
            public DocumentSearch.Hit getItem(int i) { return found.hits.get(i); }
            public long getItemId(int i) { return i; }
            public View getView(int i, View reused, ViewGroup parent) {
                LinearLayout row = (LinearLayout) reused;
                if (row == null) {
                    row = ui.vertical();
                    row.setPadding(0, ui.dp(14), 0, ui.dp(14));
                    TextView name = ui.heading("", 16);
                    name.setMaxLines(1);
                    name.setEllipsize(TextUtils.TruncateAt.END);
                    row.addView(name);
                    TextView excerpt = ui.text("", 14, ScanUi.SECONDARY);
                    excerpt.setPadding(0, ui.dp(4), 0, 0);
                    excerpt.setMaxLines(2);
                    excerpt.setEllipsize(TextUtils.TruncateAt.END);
                    row.addView(excerpt);
                }
                DocumentSearch.Hit hit = getItem(i);
                ((TextView) row.getChildAt(0)).setText(hit.name);
                ((TextView) row.getChildAt(1)).setText(hit.context);
                return row;
            }
        });
        list.setOnItemClickListener((parent, view, position, id) -> actions.open(found.hits.get(position)));
        results.addView(list, new LayoutParams(ui.match(), 0, 1));
    }
}
