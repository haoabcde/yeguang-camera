package com.scandesk.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

@RunWith(AndroidJUnit4.class)
public final class ProductNavigationFlowTest {
    private final Instrumentation device = InstrumentationRegistry.getInstrumentation();
    private ScanStore store;
    private SharedPreferences workspace;
    private Map<String, ?> previous;
    private UiAutomation windows;
    private ScanDocument source;
    private ScanDocument companion;

    @Before public void createDocuments() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        store = new ScanStore(context);
        workspace = context.getSharedPreferences("workspace", Context.MODE_PRIVATE);
        previous = workspace.getAll();
        windows = device.getUiAutomation();
        source = document("导航回归原件", "Invoice 001573", "Invoice 00742");
        companion = document("导航回归同组", "Invoice 002315");
    }

    @After public void removeDocuments() throws Exception {
        if (source == null) return;
        SharedPreferences.Editor reset = workspace.edit();
        for (Map.Entry<String, ?> entry : workspace.getAll().entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("scanReviewSource/") && source.id.equals(entry.getValue())) {
                String childId = key.substring("scanReviewSource/".length());
                ScanDocument child = store.load(childId);
                if (child != null) store.delete(child);
                reset.remove(key).remove("scanReviewSourcePages/" + childId);
            } else if (key.startsWith("scanReviewSubset/" + source.id + "/")) {
                reset.remove(key);
            }
        }
        for (String key : List.of("document", "page")) {
            Object value = previous.get(key);
            if (value instanceof String text) reset.putString(key, text);
            else reset.remove(key);
        }
        if (previous.get("crop") instanceof Boolean crop) reset.putBoolean("crop", crop);
        else reset.remove("crop");
        reset.commit();
        ScanDocument removed = store.load(source.id);
        if (removed != null) store.delete(removed);
        if (companion != null) {
            ScanDocument other = store.load(companion.id);
            if (other != null) store.delete(other);
        }
    }

    @Test public void extractingOnlySecondPageLeavesOriginalAndItsDraftIntact() throws Exception {
        List<String> originalText = List.of("第一页人工修订", "第二页人工修订");
        store.saveReview(source, ScanRecognition.WORD, new ScanRecognition.WordDraft(originalText).json());
        try (ActivityScenario<MainActivity> ignored = ActivityScenario.launch(MainActivity.class)) {
            tap("文件");
            tap(row(source));
            tap("提取文字");
            tap("调整选页");
            tap("第 1 页");
            tap("应用选页");
            node(item -> exact(item, "导航回归原件 · 已选 1 / 2 页"), "单页识别范围");
            tap("印刷");
            node(item -> exact(item, "原第 2 页 · 1/1"), "识别稿原页码");
            ScanDocument subset = onlyDraftChild();
            assertEquals(List.of(source.pages.get(1).id), pageIds(subset));
            assertEquals(pageIds(source), pageIds(store.load(source.id)));
            assertEquals(originalText, ScanRecognition.WordDraft.from(
                    store.loadReview(source, ScanRecognition.WORD)).pages);
            ScanRecognition.WordDraft extracted = ScanRecognition.WordDraft.from(
                    store.loadReview(subset, ScanRecognition.WORD));
            assertEquals(1, extracted.pages.size());
            assertTrue(extracted.pages.get(0), extracted.pages.get(0).contains("00742"));
            tap("返回");
            tap("打开原扫描件");
            tap("选页稿 · 原第 2 页");
            node(item -> exact(item, "打开原扫描件"), "重开的选页稿");
        }
    }

    @Test public void searchOpensSecondTableCellAndRefreshesAfterCorrection() throws Exception {
        String query = "NAV475920";
        OfficeTable first = new OfficeTable("第一页", 1, 1, 1, false,
                List.of(new OfficeTable.Cell(0, 0, 1, 1, "1")));
        OfficeTable second = new OfficeTable("第二页", 2, 1, 2, false,
                List.of(new OfficeTable.Cell(0, 0, 1, 1, "左侧"),
                        new OfficeTable.Cell(0, 1, 1, 1, query)));
        store.saveReview(source, ScanRecognition.EXCEL,
                new ScanRecognition.TableDraft(List.of(first, second), List.of()).checked(ScanRecognition.EXCEL).json());
        try (ActivityScenario<MainActivity> ignored = ActivityScenario.launch(MainActivity.class)) {
            tap("文件");
            tap("搜索扫描件");
            setText(node(item -> item.isEditable(), "搜索输入框"), query);
            tap("搜索");
            tap(source.name);
            node(item -> exact(item, "编辑 B1"), "第二张表的 B1 编辑框");
            AccessibilityNodeInfo field = node(item -> item.isEditable() && exact(item, query), "命中值");
            setText(field, "CORRECTED475920");
            tap("保存");
            tap("返回");
            node(item -> exact(item, "没有找到“" + query + "”"), "保留查询并更新匹配");
            ScanRecognition.TableDraft saved = ScanRecognition.TableDraft.from(
                    store.loadReview(source, ScanRecognition.EXCEL));
            assertEquals("左侧", saved.tables.get(1).cell(0, 0).text);
            assertEquals("CORRECTED475920", saved.tables.get(1).cell(0, 1).text);
        }
    }

    @Test public void cancelledBulkDeletePreservesSelectionAndBothFilesMoveTogether() throws Exception {
        try (ActivityScenario<MainActivity> ignored = ActivityScenario.launch(MainActivity.class)) {
            tap("文件");
            perform(row(source), AccessibilityNodeInfo.ACTION_LONG_CLICK);
            tap(row(companion));
            node(item -> exact(item, "已选 2 份"), "两份文件已选中");
            tap("删除");
            node(item -> exact(item, "删除所选的 2 份扫描件？"), "删除确认");
            tap("取消");
            assertNotNull(store.load(source.id));
            assertNotNull(store.load(companion.id));
            node(item -> exact(item, "已选 2 份"), "取消删除后保留选择");
            tap("移动分类");
            tap("会议资料");
            node(item -> exact(item, "分类 · 全部"), "退出选择状态");
            assertEquals(ScanDocument.Folder.MEETINGS, store.load(source.id).folder);
            assertEquals(ScanDocument.Folder.MEETINGS, store.load(companion.id).folder);
            assertEquals(pageIds(source), pageIds(store.load(source.id)));
        }
    }

    private ScanDocument document(String name, String... content) throws Exception {
        List<ScanDocument.Page> pages = new ArrayList<>();
        for (String line : content) {
            pages.add(new ScanDocument.Page(ScanStore.newId(), 0, CropQuad.FULL, ScanDocument.Filter.ORIGINAL));
        }
        ScanDocument document = store.newDocument().renamed(name).withPages(pages);
        store.save(document);
        for (int i = 0; i < pages.size(); i++) {
            Bitmap image = Bitmap.createBitmap(1400, 600, Bitmap.Config.ARGB_8888);
            try (FileOutputStream output = new FileOutputStream(store.image(document, pages.get(i)))) {
                Canvas canvas = new Canvas(image);
                canvas.drawColor(Color.WHITE);
                Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
                ink.setColor(Color.BLACK);
                ink.setTextSize(62);
                canvas.drawText(content[i], 80, 295, ink);
                assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output));
            } finally {
                image.recycle();
            }
        }
        return document;
    }

    private ScanDocument onlyDraftChild() throws Exception {
        ScanDocument child = null;
        for (Map.Entry<String, ?> entry : workspace.getAll().entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("scanReviewSource/") && source.id.equals(entry.getValue())) {
                assertTrue("预期仅生成一份选页稿", child == null);
                child = store.load(key.substring("scanReviewSource/".length()));
            }
        }
        assertNotNull(child);
        return child;
    }

    private static List<String> pageIds(ScanDocument document) {
        List<String> ids = new ArrayList<>();
        for (ScanDocument.Page page : document.pages) ids.add(page.id);
        return ids;
    }

    private static String row(ScanDocument document) {
        return document.name + "，" + document.pages.size() + " 页";
    }

    private void tap(String label) {
        perform(label, AccessibilityNodeInfo.ACTION_CLICK);
    }

    private void perform(String label, int action) {
        AccessibilityNodeInfo control = node(item -> exact(item, label)
                || String.valueOf(item.getContentDescription()).startsWith(label + "，"), label);
        while (control != null) {
            if (control.performAction(action)) {
                device.waitForIdleSync();
                return;
            }
            control = control.getParent();
        }
        throw new AssertionError("无法操作 " + label);
    }

    private void setText(AccessibilityNodeInfo field, String value) {
        Bundle text = new Bundle();
        text.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text));
        device.waitForIdleSync();
    }

    private AccessibilityNodeInfo node(Predicate<AccessibilityNodeInfo> predicate, String label) {
        long until = SystemClock.elapsedRealtime() + 40_000;
        do {
            AccessibilityNodeInfo found = find(windows.getRootInActiveWindow(), predicate);
            if (found != null) return found;
            SystemClock.sleep(80);
        } while (SystemClock.elapsedRealtime() < until);
        throw new AssertionError("找不到 " + label);
    }

    private static AccessibilityNodeInfo find(AccessibilityNodeInfo root, Predicate<AccessibilityNodeInfo> predicate) {
        if (root == null) return null;
        if (root.isVisibleToUser() && predicate.test(root)) return root;
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo match = find(root.getChild(i), predicate);
            if (match != null) return match;
        }
        return null;
    }

    private static boolean exact(AccessibilityNodeInfo node, String label) {
        return label.equals(String.valueOf(node.getText())) || label.equals(String.valueOf(node.getContentDescription()));
    }
}
