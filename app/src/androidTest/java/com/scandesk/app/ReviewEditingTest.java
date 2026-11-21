package com.scandesk.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.core.content.FileProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

@RunWith(AndroidJUnit4.class)
public final class ReviewEditingTest {
    private ScanDocument document;
    private ScanStore store;
    private SharedPreferences preferences;
    private String previousDocument, previousPage;
    private Boolean previousCrop;
    private UiAutomation windows;

    @Before public void prepareLocalScan() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        store = new ScanStore(context);
        preferences = context.getSharedPreferences("workspace", Context.MODE_PRIVATE);
        previousDocument = preferences.getString("document", null);
        previousPage = preferences.getString("page", null);
        previousCrop = preferences.contains("crop") ? preferences.getBoolean("crop", false) : null;
        ScanDocument.Page first = new ScanDocument.Page(ScanStore.newId(), 0, CropQuad.FULL, ScanDocument.Filter.ORIGINAL);
        ScanDocument.Page second = new ScanDocument.Page(ScanStore.newId(), 0, CropQuad.FULL, ScanDocument.Filter.ORIGINAL);
        document = store.newDocument().withPages(List.of(first, second)).renamed("校对回归");
        store.save(document);
        Bitmap paper = Bitmap.createBitmap(360, 480, Bitmap.Config.ARGB_8888);
        paper.eraseColor(Color.WHITE);
        try {
            for (ScanDocument.Page page : document.pages) {
                try (FileOutputStream out = new FileOutputStream(store.image(document, page))) {
                    assertTrue(paper.compress(Bitmap.CompressFormat.PNG, 100, out));
                }
            }
        } finally {
            paper.recycle();
        }
        preferences.edit().putString("document", document.id).putString("page", first.id)
                .putBoolean("crop", false).commit();
        windows = InstrumentationRegistry.getInstrumentation().getUiAutomation();
    }

    @After public void restoreWorkspace() throws Exception {
        SharedPreferences.Editor editor = preferences.edit().putString("document", previousDocument)
                .putString("page", previousPage);
        if (previousCrop == null) editor.remove("crop");
        else editor.putBoolean("crop", previousCrop);
        editor.commit();
        if (document != null) store.delete(document);
    }

    @Test public void typingPagingAndReopeningKeepsCorrections() throws Exception {
        store.saveReview(document, ScanRecognition.WORD,
                new ScanRecognition.WordDraft(List.of("待修改", "第二页")).json());
        try (ActivityScenario<MainActivity> app = ActivityScenario.launch(MainActivity.class)) {
            openTestDocument();
            tap("提取文字");
            tap("印刷");
            tap("编辑正文");
            app.onActivity(activity -> {
                EditText body = child(activity.getWindow().getDecorView(), EditText.class);
                assertTrue(body.isFocused());
                body.setText("金额 003.25");
            });
            tap("校对操作");
            tap("核对本页数字");
            node("核对数字");
            tap("取消");
            tap("下一页");
            app.onActivity(activity -> {
                EditText body = child(activity.getWindow().getDecorView(), EditText.class);
                assertEquals("第二页", body.getText().toString());
                body.setText("第二页已校对");
            });
            app.recreate();
            node("编辑正文");
            app.onActivity(activity -> assertEquals("第二页已校对",
                    child(activity.getWindow().getDecorView(), EditText.class).getText().toString()));
            tap("上一页");
            app.onActivity(activity -> assertEquals("金额 003.25",
                    child(activity.getWindow().getDecorView(), EditText.class).getText().toString()));
            assertEquals(List.of("金额 003.25", "第二页已校对"),
                    ScanRecognition.WordDraft.from(store.loadReview(document, ScanRecognition.WORD)).pages);
        }
    }

    @Test public void legacyDraftNeedsConsentAndRejectedRereadKeepsCorrections() throws Exception {
        OfficeTable sheet = new OfficeTable("旧稿", 1, 1, 1, true,
                List.of(new OfficeTable.Cell(0, 0, 1, 1, "人工更正 00107")));
        org.json.JSONObject legacy = new ScanRecognition.TableDraft(List.of(sheet), List.of()).json();
        store.saveReview(document, ScanRecognition.EXCEL, legacy);
        String before = store.loadReview(document, ScanRecognition.EXCEL).toString();
        try (ActivityScenario<MainActivity> app = ActivityScenario.launch(MainActivity.class)) {
            openTestDocument();
            tap("提取表格");
            node("手写或混合");
            tap("印刷");
            node("旧版校对稿未经过质量检查");
            tap("重新识别");
            node("第 1 页识别不可靠");
            saveEvidence("rejected-recognition");
            assertEquals(before, store.loadReview(document, ScanRecognition.EXCEL).toString());
            tap("返回原图");
            node("保存修改");
            tap("更多");
            tap("提取表格");
            tap("印刷");
            tap("继续校对旧稿");
            node("旧版识别稿未检查，导出前请逐项对照原图。");
            app.recreate();
            node("旧版识别稿未检查，导出前请逐项对照原图。");
            tap("导出 Excel");
            node("确认表格内容");
            node("导出当前草稿");
            tap("继续校对");
            assertEquals(before, store.loadReview(document, ScanRecognition.EXCEL).toString());
        }
    }

    @Test public void choosingHandwrittenContentDoesNotOpenThePrintedDraft() throws Exception {
        OfficeTable print = new OfficeTable("印刷稿", 1, 1, 1, true,
                List.of(new OfficeTable.Cell(0, 0, 1, 1, "印刷内容")));
        OfficeTable hand = new OfficeTable("手写稿", 1, 1, 1, true,
                List.of(handCell(0, 0, "手写更正 00107", true)));
        store.saveReview(document, ScanRecognition.EXCEL,
                new ScanRecognition.TableDraft(List.of(print), List.of()).checked(ScanRecognition.EXCEL).json());
        store.saveReview(document, ScanRecognition.HAND_TABLE,
                new ScanRecognition.TableDraft(List.of(hand), List.of()).checked(ScanRecognition.HAND_TABLE).json());
        try (ActivityScenario<MainActivity> app = ActivityScenario.launch(MainActivity.class)) {
            openTestDocument();
            tap("提取表格");
            saveEvidence("mode-picker");
            tap("手写或混合");
            node("校对手写表格");
            saveEvidence("handwritten-review");
            app.recreate();
            node("校对手写表格");
            assertEquals("手写更正 00107", handTables().get(0).cell(0, 0).text);
        }
    }

    private void saveEvidence(String name) throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        SystemClock.sleep(300); // Let the window animation reach the actual observed dialog.
        Bitmap screenshot = windows.takeScreenshot();
        if (screenshot == null) throw new AssertionError("No actual screenshot");
        Context context = ApplicationProvider.getApplicationContext();
        File directory = new File(context.getExternalFilesDir("acceptance"), "rc14");
        ScanStore.ensureDirectory(directory);
        try (FileOutputStream out = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, out));
        } finally { screenshot.recycle(); }
    }

    @Test public void editingDistantCellKeepsItsViewportAndDraft() throws Exception {
        int rows = 36, columns = 10;
        List<OfficeTable.Cell> cells = new ArrayList<>();
        for (int row = 0; row < rows; row++) for (int col = 0; col < columns; col++)
            cells.add(new OfficeTable.Cell(row, col, 1, 1, "1"));
        OfficeTable sheet = new OfficeTable("采购表", 1, rows, columns, false, cells);
        store.saveReview(document, ScanRecognition.EXCEL,
                new ScanRecognition.TableDraft(List.of(sheet), List.of()).checked(ScanRecognition.EXCEL).json());
        try (ActivityScenario<MainActivity> app = ActivityScenario.launch(MainActivity.class)) {
            openTestDocument();
            tap("提取表格");
            tap("印刷");
            node("导出 Excel");
            app.onActivity(activity -> {
                TableGridView grid = child(activity.getWindow().getDecorView(), TableGridView.class);
                HorizontalScrollView across = (HorizontalScrollView) grid.getParent();
                ScrollView down = (ScrollView) across.getParent();
                across.scrollTo(grid.getWidth(), 0);
                down.scrollTo(0, grid.getHeight());
                assertTrue(across.getScrollX() > 0 && down.getScrollY() > 0);
                assertTrue(grid.getAccessibilityNodeProvider().performAction(rows * columns,
                        AccessibilityNodeInfo.ACTION_CLICK, null));
            });
            node("编辑 J36");
            Bundle replacement = new Bundle();
            replacement.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "0098.70");
            assertTrue(node("android.widget.EditText").performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, replacement));
            tap("保存");
            node("导出 Excel");
            app.onActivity(activity -> {
                TableGridView grid = child(activity.getWindow().getDecorView(), TableGridView.class);
                HorizontalScrollView across = (HorizontalScrollView) grid.getParent();
                assertTrue("列位置", across.getScrollX() > 0);
                assertTrue("行位置", ((ScrollView) across.getParent()).getScrollY() > 0);
            });
            assertEquals("0098.70", ScanRecognition.TableDraft.from(
                    store.loadReview(document, ScanRecognition.EXCEL)).tables.get(0).cell(rows - 1, columns - 1).text);
        }
    }

    @Test public void blankRereadAfterPageChangeKeepsPriorCorrection() throws Exception {
        List<String> original = List.of("已校对的票号 00138", "保留此页");
        store.saveReview(document, ScanRecognition.WORD, new ScanRecognition.WordDraft(original).json());
        ScanDocument updated = document.withPages(List.of(document.pages.get(0).rotate(), document.pages.get(1)));
        store.save(updated);
        try (ActivityScenario<MainActivity> app = ActivityScenario.launch(MainActivity.class)) {
            openTestDocument();
            tap("提取文字");
            tap("印刷");
            tap("重新识别");
            node("重新识别没有读到文字，已保留原校对稿");
            assertEquals(original, ScanRecognition.WordDraft.from(
                    store.loadReview(document, ScanRecognition.WORD)).pages);
            assertTrue(store.reviewIsOutdated(updated, ScanRecognition.WORD));
        }
    }

    @Test public void verifiesHighAndLowConfidenceHandCellsAndKeepsPrintedDraftSeparate() throws Exception {
        OfficeTable.Cell first = new OfficeTable.Cell(0, 0, 1, 1, "00101");
        first.type = OfficeTable.CellType.TEXT;
        first.sourceBounds = new RectF(.08f, .12f, .5f, .3f);
        first.needsReview = true;
        first.alternative = "00107";
        OfficeTable.Cell second = new OfficeTable.Cell(0, 1, 1, 1, "00999");
        second.type = OfficeTable.CellType.TEXT;
        second.sourceBounds = new RectF(.45f, .3f, .85f, .55f);
        List<OfficeTable> pages = List.of(new OfficeTable("第一页", 1, 1, 1, false, List.of(first)),
                new OfficeTable("第二页", 2, 1, 2, false, List.of(new OfficeTable.Cell(0, 0, 1, 1, "标题"), second)));
        store.saveReview(document, ScanRecognition.HAND_TABLE, new ScanRecognition.TableDraft(pages, List.of()).checked(ScanRecognition.HAND_TABLE).json());
        OfficeTable print = new OfficeTable("印刷表", 1, 1, 1, false, List.of(new OfficeTable.Cell(0, 0, 1, 1, "原校对稿")));
        store.saveReview(document, ScanRecognition.EXCEL, new ScanRecognition.TableDraft(List.of(print), List.of()).checked(ScanRecognition.EXCEL).json());
        try (ActivityScenario<MainActivity> app = ActivityScenario.launch(MainActivity.class)) {
            openTestDocument();
            tap("提取表格");
            tap("印刷");
            tap("校对操作");
            tap("按手写重新识别");
            tap("打开已有稿");
            node("校对手写表格");
            exportWithoutConfirmingCells();
            tap("导出 Excel");
            node("确认表格内容");
            node("还有 2 格未核对，其中 1 格存疑。\n请核对编号、金额及行列对应。");
            tap("继续校对");
            node("编辑 A1");
            node("单元格原图局部，可缩放");
            tap("采用候选，00107");
            assertEquals("00101", handTables().get(0).cell(0, 0).text);
            tap("确认");
            node("存疑项已核对");
            OfficeTable.Cell confirmed = handTables().get(0).cell(0, 0);
            assertEquals("00107", confirmed.text);
            assertEquals(first.sourceBounds, confirmed.sourceBounds);
            assertTrue(confirmed.reviewed);
            tap("核对其余项");
            node("编辑 B1");
            tap("取消");
            app.recreate();
            node("校对手写表格");
            node("逐项核对，1 格未核对");
            tap("校对操作");
            tap("逐项核对");
            node("编辑 B1");
            assertFalse(handTables().get(1).cell(0, 1).needsReview);
            tap("确认");
            node("已完成核对");
            assertTrue(handTables().get(1).cell(0, 1).reviewed);
            assertEquals(OfficeTable.CellType.TEXT, handTables().get(1).cell(0, 1).type);
            tap("校对操作");
            tap("打开印刷表格");
            node("校对印刷表格");
            assertEquals("原校对稿", ScanRecognition.TableDraft.from(
                    store.loadReview(document, ScanRecognition.EXCEL)).tables.get(0).cell(0, 0).text);
        }
    }

    @Test public void prioritizesUncertainCellsAndCanReopenAConfirmation() throws Exception {
        OfficeTable.Cell good = handCell(0, 0, "00123", false);
        OfficeTable.Cell suspect = handCell(0, 1, "00455", true);
        OfficeTable.Cell later = handCell(0, 0, "00899", true);
        List<OfficeTable> tables = List.of(
                new OfficeTable("第一页", 1, 1, 2, true, List.of(good, suspect)),
                new OfficeTable("第二页", 2, 1, 1, true, List.of(later)));
        store.saveReview(document, ScanRecognition.HAND_TABLE, new ScanRecognition.TableDraft(tables, List.of()).checked(ScanRecognition.HAND_TABLE).json());
        try (ActivityScenario<MainActivity> app = ActivityScenario.launch(MainActivity.class)) {
            openTestDocument();
            tap("提取表格");
            tap("手写或混合");
            tap("校对操作");
            tap("先核对存疑项（2）");
            node("编辑 B1");
            tap("确认下一项");
            node("编辑 A1");
            tap("确认");
            node("存疑项已核对");
            assertFalse(handTables().get(0).cell(0, 0).reviewed);
            assertTrue(handTables().get(0).cell(0, 1).reviewed);
            assertTrue(handTables().get(1).cell(0, 0).reviewed);
            tap("返回表格");
            tap("A1 00899，已核对");
            tap("重新标为未核对");
            assertFalse(handTables().get(1).cell(0, 0).reviewed);
            app.recreate();
            node("逐项核对，2 格未核对");
            tap("校对操作");
            tap("先核对存疑项（1）");
            node("编辑 A1");
            tap("确认");
            tap("核对其余项");
            node("编辑 A1");
            node("00123");
            tap("确认");
            node("已完成核对");
        }
    }

    @Test public void confirmingNextContinuesFromCurrentCellBeforeWrapping() throws Exception {
        List<OfficeTable.Cell> cells = List.of(handCell(0, 0, "A", false),
                handCell(1, 0, "B", false), handCell(2, 0, "C", false));
        OfficeTable table = new OfficeTable("顺序", 1, 3, 1, true, cells);
        store.saveReview(document, ScanRecognition.HAND_TABLE,
                new ScanRecognition.TableDraft(List.of(table), List.of()).checked(ScanRecognition.HAND_TABLE).json());
        try (ActivityScenario<MainActivity> app = ActivityScenario.launch(MainActivity.class)) {
            openTestDocument();
            tap("提取表格");
            tap("手写或混合");
            tap("A2 B，未核对");tap("确认下一项");
            node("编辑 A3");
            tap("确认下一项");
            node("编辑 A1");
            tap("取消");
            assertFalse(handTables().get(0).cell(0, 0).reviewed);
            assertTrue(handTables().get(0).cell(1, 0).reviewed);
            assertTrue(handTables().get(0).cell(2, 0).reviewed);
        }
    }

    private static OfficeTable.Cell handCell(int row, int column, String text, boolean uncertain) {
        OfficeTable.Cell cell = new OfficeTable.Cell(row, column, 1, 1, text);
        cell.type = OfficeTable.CellType.TEXT;
        cell.sourceBounds = new RectF(.1f, .1f, .6f, .4f);
        cell.needsReview = uncertain;
        return cell;
    }

    @Test public void columnPreviewCanCancelOrApplyWithoutDiscardingConfirmedText() throws Exception {
        OfficeTable.Cell left = handCell(0, 0, "000123", false);
        left.reviewed = true;
        left.sourceBounds = new RectF(.08f, .1f, .26f, .15f);
        OfficeTable.Cell right = handCell(1, 0, "000456", true);
        right.sourceBounds = new RectF(.60f, .1f, .78f, .15f);
        OfficeTable original = new OfficeTable("分列校对", 1, 2, 1, true, List.of(left, right));
        store.saveReview(document, ScanRecognition.HAND_TABLE,
                new ScanRecognition.TableDraft(List.of(original), List.of()).checked(ScanRecognition.HAND_TABLE).json());
        try (ActivityScenario<MainActivity> app = ActivityScenario.launch(MainActivity.class)) {
            openTestDocument();
            tap("提取表格");
            tap("手写或混合");
            tap("校对操作");tap("按原图调整分列");
            node("本页原图，可缩放");
            tap("分列方式");tap("2 列");
            node("1 行 · 2 列 · 保留 2 项");
            tap("取消");
            assertEquals(1, handTables().get(0).columns);
            tap("校对操作");tap("按原图调整分列");
            tap("分列方式");tap("2 列");
            node("1 行 · 2 列 · 保留 2 项");
            tap("应用分列");
            node("1 行 · 2 列");
            OfficeTable applied = handTables().get(0);
            assertEquals("000123", applied.cell(0, 0).text);
            assertTrue(applied.cell(0, 0).reviewed);
            assertEquals("000456", applied.cell(0, 1).text);
            assertTrue(applied.cell(0, 1).unreviewed());
            assertEquals(right.sourceBounds, applied.cell(0, 1).sourceBounds);
            app.recreate();
            node("1 行 · 2 列");
            node("逐项核对，1 格未核对");
            tap("校对操作");tap("按原图调整分列");
            tap("分列方式");
            node("android.widget.ListView").performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            tap("自动按原图分列");
            node("1 行 · 2 列 · 保留 2 项");
            tap("应用分列");
            assertEquals(2, handTables().get(0).columns);
        }
    }

    private List<OfficeTable> handTables() throws Exception {
        return ScanRecognition.TableDraft.from(store.loadReview(document, ScanRecognition.HAND_TABLE)).tables;
    }

    private void exportWithoutConfirmingCells() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        File folder = new File(context.getCacheDir(), "exports");
        ScanStore.ensureDirectory(folder);
        File output = File.createTempFile("unreviewed-hand-", ".xlsx", folder);
        Intent result = new Intent().setData(FileProvider.getUriForFile(context, context.getPackageName() + ".files", output));
        IntentFilter filter = new IntentFilter(Intent.ACTION_CREATE_DOCUMENT);
        filter.addCategory(Intent.CATEGORY_OPENABLE);
        filter.addDataType("*/*");
        Instrumentation device = InstrumentationRegistry.getInstrumentation();
        Instrumentation.ActivityMonitor monitor = device.addMonitor(filter,
                new Instrumentation.ActivityResult(Activity.RESULT_OK, result), true);
        try {
            tap("导出 Excel");
            tap("导出当前草稿");
            node("文件已保存");
            try (ZipFile zip = new ZipFile(output)) {
                String xml = part(zip, "xl/worksheets/sheet1.xml");
                assertTrue(xml.contains("inlineStr") && xml.contains("00101"));
            }
            assertFalse(handTables().get(0).cell(0, 0).reviewed);
            tap("完成");
        } finally {
            device.removeMonitor(monitor);
            output.delete();
        }
    }

    @Test public void readsLegacyCellsAndCutsNormalizedOriginalPixels() throws Exception {
        org.json.JSONObject legacy = new org.json.JSONObject("{\"name\":\"旧表\",\"page\":1,\"rows\":1,\"columns\":1,"
                + "\"cells\":[[0,0,1,1,\"0001\",\"TEXT\"]]}");
        OfficeTable.Cell old = OfficeTable.from(legacy).cell(0, 0);
        assertFalse(old.unreviewed());
        assertEquals("", old.alternative);
        assertEquals("0001", old.text);
        Bitmap source = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888);
        source.eraseColor(Color.WHITE);
        source.setPixel(21, 12, Color.RED);
        Bitmap slice = MainActivity.cellImage(source, new RectF(.25f,.25f,.75f,.75f));
        try {
            assertEquals(40, slice.getWidth());
            assertEquals(20, slice.getHeight());
            assertEquals(Color.RED, slice.getPixel(1, 2));
        } finally {
            slice.recycle();
            source.recycle();
        }
    }

    @Test public void exportsPreviousWordAndWorkbookInOriginalOrder() throws Exception {
        List<String> paragraphs = List.of("旧第一页金额 00138", "旧第二页金额 120.25");
        store.saveReview(document, ScanRecognition.WORD, new ScanRecognition.WordDraft(paragraphs).json());
        List<OfficeTable> sheets = new ArrayList<>();
        for (int i = 0; i < paragraphs.size(); i++) {
            sheets.add(new OfficeTable("原第" + (i + 1) + "页", i + 1, 1, 1, false,
                    List.of(new OfficeTable.Cell(0, 0, 1, 1, paragraphs.get(i)))));
        }
        store.saveReview(document, ScanRecognition.EXCEL,
                new ScanRecognition.TableDraft(sheets, List.of()).checked(ScanRecognition.EXCEL).json());
        ScanDocument reordered = document.withPages(List.of(document.pages.get(1), document.pages.get(0)));
        store.save(reordered);
        try (ActivityScenario<MainActivity> app = ActivityScenario.launch(MainActivity.class)) {
            openTestDocument();
            for (boolean excel : List.of(false, true)) {
                Context context = ApplicationProvider.getApplicationContext();
                File exports = new File(context.getCacheDir(), "exports");
                ScanStore.ensureDirectory(exports);
                File output = File.createTempFile("review-test-", excel ? ".xlsx" : ".docx", exports);
                Intent result = new Intent().setData(FileProvider.getUriForFile(context,
                        context.getPackageName() + ".files", output));
                IntentFilter filter = new IntentFilter(Intent.ACTION_CREATE_DOCUMENT);
                filter.addCategory(Intent.CATEGORY_OPENABLE);
                filter.addDataType("*/*");
                Instrumentation device = InstrumentationRegistry.getInstrumentation();
                Instrumentation.ActivityMonitor picker = device.addMonitor(filter,
                        new Instrumentation.ActivityResult(Activity.RESULT_OK, result), true);
                try {
                    tap(excel ? "提取表格" : "提取文字");
                    tap("印刷");
                    tap("查看旧稿");
                    node("原图更新前的识别稿");
                    tap("导出旧稿");
                    node("文件已保存");
                    assertEquals(1, picker.getHits());
                    try (ZipFile file = new ZipFile(output)) {
                        if (excel) {
                            assertTrue(part(file, "xl/worksheets/sheet1.xml").contains(paragraphs.get(0)));
                            assertTrue(part(file, "xl/worksheets/sheet2.xml").contains(paragraphs.get(1)));
                        } else {
                            String body = part(file, "word/document.xml");
                            int first = body.indexOf(paragraphs.get(0));
                            assertTrue(first >= 0 && body.indexOf(paragraphs.get(1)) > first);
                            assertTrue(body.contains("pageBreakBefore"));
                        }
                    }
                    assertTrue(store.reviewIsOutdated(reordered, excel ? ScanRecognition.EXCEL : ScanRecognition.WORD));
                    tap("完成");
                } finally {
                    device.removeMonitor(picker);
                    output.delete();
                }
            }
        }
    }

    private static String part(ZipFile file, String path) throws java.io.IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                file.getInputStream(file.getEntry(path)), StandardCharsets.UTF_8))) {
            return reader.lines().collect(java.util.stream.Collectors.joining());
        }
    }

    private void openTestDocument() {
        tap("文件");
        tap(document.name + "，" + document.pages.size() + " 页");
    }

    private void tap(String label) {
        AccessibilityNodeInfo control = node(label);
        while (!control.isClickable() && control.getParent() != null) control = control.getParent();
        assertTrue(label, control.performAction(AccessibilityNodeInfo.ACTION_CLICK));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private AccessibilityNodeInfo node(String label) {
        long until = SystemClock.elapsedRealtime() + 15000;
        do {
            AccessibilityNodeInfo result = find(windows.getRootInActiveWindow(), label);
            if (result != null) return result;
            SystemClock.sleep(80);
        } while (SystemClock.elapsedRealtime() < until);
        throw new AssertionError("找不到控件 " + label);
    }

    private static AccessibilityNodeInfo find(AccessibilityNodeInfo root, String label) {
        if (root == null) return null;
        String description = String.valueOf(root.getContentDescription());
        if (label.contentEquals(String.valueOf(root.getText())) || label.contentEquals(String.valueOf(root.getClassName()))
                || description.equals(label) || description.startsWith(label + "，")) return root;
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo result = find(root.getChild(i), label);
            if (result != null) return result;
        }
        return null;
    }

    private static <T extends View> T child(View root, Class<T> type) {
        if (type.isInstance(root)) return type.cast(root);
        if (root instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) root;
            for (int i = 0; i < parent.getChildCount(); i++) {
                T found = child(parent.getChildAt(i), type);
                if (found != null) return found;
            }
        }
        return null;
    }
}
