package com.scandesk.app;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public final class EditorWorkflowTest {
    @Test public void batchEditorSeparatesCropApplyContinueCaptureAndTaskCompletion() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            ScanDocument.Page page = new ScanDocument.Page(ScanStore.newId(), 0,
                    CropQuad.FULL, ScanDocument.Filter.ORIGINAL);
            ScanDocument doc = new ScanStore(context).newDocument().withPages(List.of(page));
            List<EditorView.Action> calls = new ArrayList<>();
            Bitmap bitmap = Bitmap.createBitmap(120, 180, Bitmap.Config.ARGB_8888);
            try {
                for (boolean landscape : new boolean[]{false, true}) {
                calls.clear();
                ScanUi ui = new ScanUi(context);
                EditorView view = new EditorView(context, new ScanUi(context), doc, page,
                        CropQuad.FULL, true, landscape, new EditorView.Controller() {
                    public void action(EditorView.Action action) { calls.add(action); }
                    public void select(ScanDocument.Page item) {}
                    public void move(String from, String to) {}
                    public void cropChanged(CropQuad crop) {}
                    public void load(ScanDocument.Page item, boolean original, boolean thumb, EditorView.ImageResult result) {
                        result.loaded(bitmap);
                    }
                });
                view.configureWorkflow("提取表格（1 页）", "提取表格 · 手写或混合", true);
                assertEquals(View.GONE, find(view, "导出").getVisibility());
                int width = ui.dp(landscape ? 780 : 360);
                int height = ui.dp(landscape ? 300 : 740);
                view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                view.layout(0, 0, width, height);
                View next = find(view, "提取表格（1 页）");
                android.graphics.Rect bounds = new android.graphics.Rect();
                next.getDrawingRect(bounds);
                view.offsetDescendantRectToMyCoords(next, bounds);
                assertTrue("Task action must be visible: " + bounds, bounds.top >= 0 && bounds.bottom <= height);

                assertTrue(find(view, "应用裁切").performClick());
                assertTrue(find(view, "取消裁切").performClick());
                assertTrue(find(view, "继续拍摄").performClick());
                assertTrue(find(view, "提取表格（1 页）").performClick());
                assertEquals(List.of(EditorView.Action.APPLY_CROP, EditorView.Action.CANCEL_CROP,
                        EditorView.Action.CONTINUE_CAPTURE, EditorView.Action.COMPLETE), calls);
                }
            } finally { bitmap.recycle(); }
        });
    }

    private static View find(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView)view).getText())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = find(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }
}
