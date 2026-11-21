package com.scandesk.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

@RunWith(AndroidJUnit4.class)
public final class CaptureRecoveryFlowTest {
    private static final String RECOVERY_BUTTON = "恢复待导入的拍摄批次";
    private static final String STAGED_FILES = "pendingCapture.files";
    private static final String STAGED_DOCUMENT = "pendingCapture.id";
    private static final List<String> WORKSPACE_KEYS = List.of("document", "page", "crop",
            STAGED_FILES, STAGED_DOCUMENT, "pendingCapture.action", "pendingCapture.mode",
            "pendingCapture.handwriting", "pendingCapture.shortcut");
    private final Instrumentation device = InstrumentationRegistry.getInstrumentation();

    @Test public void partialBatchCanBeRecoveredAfterReopeningWithoutDuplicatingTheSavedPage()
            throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        ScanStore store = new ScanStore(context);
        SharedPreferences workspace = context.getSharedPreferences("workspace", Context.MODE_PRIVATE);
        Map<String, ?> before = workspace.getAll();
        File incoming = new File(context.getFilesDir(), "incoming");
        ScanStore.ensureDirectory(incoming);
        String firstId = ScanStore.newId();
        String secondId = ScanStore.newId();
        File first = new File(incoming, firstId + ".jpg");
        File second = new File(incoming, secondId + ".jpg");
        assertTrue(workspace.edit().remove(STAGED_FILES).remove(STAGED_DOCUMENT).commit());
        String createdId = null;
        try {
            writePhoto(first);
            Files.write(second.toPath(), new byte[]{1, 17, 29});
            Intent result = new Intent().putExtra(CaptureActivity.EXTRA_WORK_MODE, "file")
                    .putExtra(CaptureActivity.EXTRA_HANDWRITING, false)
                    .putStringArrayListExtra(CaptureActivity.EXTRA_FILES,
                            new ArrayList<>(List.of(first.getName(), second.getName())));
            Instrumentation.ActivityMonitor camera = device.addMonitor(CaptureActivity.class.getName(),
                    new Instrumentation.ActivityResult(Activity.RESULT_OK, result), true);
            try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
                click(screen, "拍照扫描");
                assertEquals(1, camera.getHits());
                await("显示恢复入口并只保留失败原图", () -> hasControl(screen, RECOVERY_BUTTON)
                        && !first.exists() && second.isFile());
            } finally {
                device.removeMonitor(camera);
            }

            String id = workspace.getString(STAGED_DOCUMENT, null);
            assertNotNull(id);
            createdId = id;
            assertEquals(second.getName(), workspace.getString(STAGED_FILES, null));
            ScanDocument partial = store.load(id);
            assertNotNull(partial);
            assertEquals(List.of(firstId), pageIds(partial));
            assertTrue(store.image(partial, partial.pages.get(0)).isFile());
            // Save a non-default edit to the already imported page before reopening the workspace.
            CropQuad edited = CropQuad.FULL.move(0, .14f, .12f);
            store.save(partial.withPages(List.of(partial.pages.get(0).crop(edited))));
            writePhoto(second);

            try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
                await("重新打开后保留恢复入口", () -> hasControl(screen, RECOVERY_BUTTON));
                click(screen, RECOVERY_BUTTON);
                clickDialog("继续导入");
                await("显示补齐后的编辑器并清理已导入的原图", () ->
                        !workspace.contains(STAGED_FILES) && !second.exists()
                                && hasControl(screen, "保存文档"));
                assertFalse(hasControl(screen, RECOVERY_BUTTON));
                ScanDocument recovered = store.load(id);
                assertNotNull(recovered);
                assertEquals(List.of(firstId, secondId), pageIds(recovered));
                assertEquals(edited, recovered.pages.get(0).crop);
                assertFalse(first.exists());
                assertTrue(store.image(recovered, recovered.pages.get(1)).isFile());
            }
        } finally {
            String id = createdId != null ? createdId : workspace.getString(STAGED_DOCUMENT, null);
            try {
                if (id != null) {
                    ScanDocument created = store.load(id);
                    if (created != null) store.delete(created);
                }
            } finally {
                first.delete();
                second.delete();
                restoreWorkspace(workspace, before);
            }
        }
    }

    private static List<String> pageIds(ScanDocument document) {
        List<String> ids = new ArrayList<>();
        for (ScanDocument.Page page : document.pages) ids.add(page.id);
        return ids;
    }

    private static void writePhoto(File destination) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(240, 320, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xffeeeeee);
        try (FileOutputStream out = new FileOutputStream(destination)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out));
        } finally {
            bitmap.recycle();
        }
    }

    private static void restoreWorkspace(SharedPreferences workspace, Map<String, ?> before) {
        SharedPreferences.Editor restore = workspace.edit();
        for (String key : WORKSPACE_KEYS) {
            Object value = before.get(key);
            if (value instanceof String text) restore.putString(key, text);
            else if (value instanceof Boolean flag) restore.putBoolean(key, flag);
            else restore.remove(key);
        }
        assertTrue(restore.commit());
    }

    private static void click(ActivityScenario<MainActivity> screen, String label) {
        screen.onActivity(activity -> {
            View control = findControl(activity.findViewById(android.R.id.content), label);
            assertNotNull(label, control);
            assertTrue(label, control.performClick());
        });
    }

    private static boolean hasControl(ActivityScenario<MainActivity> screen, String label) {
        AtomicBoolean found = new AtomicBoolean();
        screen.onActivity(activity -> found.set(
                findControl(activity.findViewById(android.R.id.content), label) != null));
        return found.get();
    }

    private static View findControl(View view, String label) {
        if (view.isClickable() && (label.contentEquals(String.valueOf(view.getContentDescription()))
                || view instanceof TextView text && label.contentEquals(text.getText()))) return view;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                View match = findControl(group.getChildAt(i), label);
                if (match != null) return match;
            }
        }
        return null;
    }

    private void clickDialog(String label) {
        await("点击「" + label + "」", () -> {
            AccessibilityNodeInfo button = findDialogButton(device.getUiAutomation().getRootInActiveWindow(), label);
            return button != null && button.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        });
    }

    private static AccessibilityNodeInfo findDialogButton(AccessibilityNodeInfo node, String label) {
        if (node == null) return null;
        if (node.isClickable() && label.contentEquals(String.valueOf(node.getText()))) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo match = findDialogButton(node.getChild(i), label);
            if (match != null) return match;
        }
        return null;
    }

    private static void await(String expectation, BooleanSupplier complete) {
        long deadline = SystemClock.uptimeMillis() + 20_000;
        while (SystemClock.uptimeMillis() < deadline) {
            if (complete.getAsBoolean()) return;
            SystemClock.sleep(50);
        }
        fail(expectation);
    }
}
