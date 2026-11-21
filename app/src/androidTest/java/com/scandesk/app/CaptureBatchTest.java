package com.scandesk.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.lifecycle.ViewModelProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public final class CaptureBatchTest {
    @Test public void savedShotsStayInCameraAndMoreToolsReturnsTheWholeBatchAfterRecreation() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        File directory = new File(context.getFilesDir(), "incoming");
        ScanStore.ensureDirectory(directory);
        File first = new File(directory, ScanStore.newId() + ".jpg");
        File second = new File(directory, ScanStore.newId() + ".jpg");
        Intent request = new Intent(context, CaptureActivity.class);
        try {
            writePhoto(first);
            writePhoto(second);
            try (ActivityScenario<CaptureActivity> screen = ActivityScenario.launchActivityForResult(request)) {
                screen.onActivity(activity -> {
                    View decor = activity.getWindow().getDecorView();
                    assertTrue(control(decor, "扫描文件").isSelected());
                    assertFalse(byDescription(decor, "编辑 0 页").isEnabled());
                    reportSavedShot(activity, first);
                    assertFalse(activity.isFinishing());
                    assertTrue(byDescription(decor, "编辑 1 页").isEnabled());
                    reportSavedShot(activity, second);
                    assertFalse(activity.isFinishing());
                    assertTrue(byDescription(decor, "编辑 2 页").isEnabled());
                    control(decor, "提取表格").performClick();
                    control(decor, "手写或混合").performClick();
                    control(decor, "扫码").performClick();
                    assertTrue(control(decor, "提取表格").isSelected());
                });
                screen.recreate();
                screen.onActivity(activity -> {
                    View decor = activity.getWindow().getDecorView();
                    assertTrue(control(decor, "提取表格").isSelected());
                    assertTrue(control(decor, "手写或混合").isSelected());
                    assertTrue(byDescription(decor, "编辑 2 页").isEnabled());
                    control(decor, "更多工具").performClick();
                });
                Instrumentation.ActivityResult result = screen.getResult();
                assertEquals(Activity.RESULT_OK, result.getResultCode());
                Intent data = result.getResultData();
                assertEquals(CaptureActivity.DEST_TOOLS, data.getStringExtra(CaptureActivity.EXTRA_DESTINATION));
                assertEquals("table", data.getStringExtra(CaptureActivity.EXTRA_WORK_MODE));
                assertTrue(data.getBooleanExtra(CaptureActivity.EXTRA_HANDWRITING, false));
                assertEquals(List.of(first.getName(), second.getName()),
                        data.getStringArrayListExtra(CaptureActivity.EXTRA_FILES));
                assertTrue(first.isFile());
                assertTrue(second.isFile());
            }
        } finally {
            first.delete();
            second.delete();
        }
    }

    // Drive the real Activity observer at the saved and quality-approved boundary.
    private static void reportSavedShot(CaptureActivity activity, File photo) {
        CaptureReview review = new ViewModelProvider(activity).get(CaptureReview.class);
        review.begin(photo);
        review.stage.setValue(CaptureReview.Stage.READY);
    }

    private static void writePhoto(File destination) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(128, 192, Bitmap.Config.ARGB_8888);
        try (FileOutputStream out = new FileOutputStream(destination)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out));
        } finally {
            bitmap.recycle();
        }
    }

    private static View control(View root, String label) {
        View view = find(root, label, false);
        if (view == null) throw new AssertionError("Missing camera control " + label);
        return view;
    }

    private static View byDescription(View root, String label) {
        View view = find(root, label, true);
        if (view == null) throw new AssertionError("Missing camera control " + label);
        return view;
    }

    private static View find(View view, String label, boolean contentDescription) {
        CharSequence value = contentDescription ? view.getContentDescription()
                : view instanceof TextView ? ((TextView) view).getText() : null;
        if (value != null && label.contentEquals(value)) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = find(group.getChildAt(i), label, contentDescription);
                if (child != null) return child;
            }
        }
        return null;
    }
}
