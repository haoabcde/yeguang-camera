package com.scandesk.app;

import static org.junit.Assert.*;
import android.app.UiAutomation;
import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public final class ExportHistoryFlowTest {
    @Test public void sameNamedLinkedExportsKeepTheirOwnIdentityWhenRemovingTheOlderRecord() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        ScanStore store = new ScanStore(context);
        ScanDocument source = store.newDocument().renamed("导出记录回归");
        store.save(source);
        ExportHistory history = new ExportHistory(context);
        String name = "same-named-report.xlsx";
        history.record(source.id, name, "XLSX", Uri.parse("content://qa.exports/older"), 120);
        String older = history.list().get(0).id;
        history.record(source.id, name, "XLSX", Uri.parse("content://qa.exports/newer"), 240);
        String newer = history.list().get(0).id;
        UiAutomation windows = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        try (ActivityScenario<MainActivity> app = ActivityScenario.launch(MainActivity.class)) {
            app.onActivity(activity -> {
                try {
                    Method linked = MainActivity.class.getDeclaredMethod("linkedExports", ScanDocument.class);
                    linked.setAccessible(true);
                    linked.invoke(activity, source);
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            });
            List<AccessibilityNodeInfo> records = waitFor(windows, name, 2);
            click(records.get(1)); // Newest-first list: explicitly open the older entry.
            click(waitFor(windows, "移除导出记录", 1).get(0));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            List<ExportHistory.Entry> remaining = history.list();
            assertTrue(remaining.stream().anyMatch(entry -> newer.equals(entry.id)));
            assertFalse(remaining.stream().anyMatch(entry -> older.equals(entry.id)));
        } finally {
            history.remove(older);
            history.remove(newer);
            store.delete(source);
        }
    }

    private static List<AccessibilityNodeInfo> waitFor(UiAutomation windows, String label, int count) {
        for (int attempt = 0; attempt < 60; attempt++) {
            List<AccessibilityNodeInfo> result = new ArrayList<>();
            collect(windows.getRootInActiveWindow(), label, result);
            if (result.size() == count) return result;
            SystemClock.sleep(100);
        }
        throw new AssertionError("Missing " + count + " controls: " + label);
    }

    private static void collect(AccessibilityNodeInfo node, String label, List<AccessibilityNodeInfo> result) {
        if (node == null) return;
        if (label.contentEquals(node.getText() == null ? "" : node.getText())
                || label.contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription())) {
            result.add(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) collect(node.getChild(i), label, result);
    }

    private static void click(AccessibilityNodeInfo node) {
        while (node != null) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;
            node = node.getParent();
        }
        throw new AssertionError("Control cannot be clicked");
    }
}
