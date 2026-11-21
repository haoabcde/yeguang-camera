package com.scandesk.app;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.FileProvider;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.File;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class MainActivity extends ComponentActivity {
    private static final int PICK_IMAGES = 100;
    private static final int TAKE_IMAGE = 101;
    private static final int SAVE_FILE = 102;
    private static final Pattern DEFAULT_SCAN_NAME =
            Pattern.compile("^扫描件 \\d{4}-(\\d{2})-(\\d{2}) (\\d{2})\\.(\\d{2})$");
    private enum Screen { LIBRARY, EDITOR, WORD, EXCEL, TOOLS, PDF, FOLDER, SEARCH }
    private enum PdfTool { OPEN, ADD, MERGE, PAGES, SAVE, SPLIT, TEXT, WORD, EXCEL, PNG, IMAGES, LONG,
        COMPRESS, WATERMARK, SIGN }
    private enum ImageTool { EDIT, CROP, ENHANCE, SCREEN, WORD, HANDWRITING, TABLE, HAND_TABLE, QR, RESIZE, PASSPORT, LONG,
        WATERMARK, SIGN, LINE, ID, BOOK, REPAIR }

    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final ExecutorService previews = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> images = new LruCache<>(22 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
    };
    private final Runnable saveWord = () -> saveWordDraft(true);

    private ScanStore store;
    private PdfWorkspace pdfStore;
    private ScanUi ui;
    private SharedPreferences preferences;
    private FrameLayout root;
    private Screen screen = Screen.LIBRARY;
    private ScanDocument document;
    private String selectedPage;
    private boolean cropping;
    private CropQuad pendingCrop;
    private EditorView editor;
    private LibrarySearchView searchView;
    private String searchInput = "", searchQuery = "";
    private DocumentSearch.Results searchResults;
    private volatile int contentGeneration;
    private JobState job;
    private List<PdfWorkspace.Page> pdfPages = new ArrayList<>();
    private boolean pdfLoaded;
    private int pdfPage;
    private List<PdfWorkspace.Page> undoPdfPages;
    private int undoPdfPage;
    private PdfTool nextPdfTool = PdfTool.OPEN;
    private ImageTool nextImageTool = ImageTool.EDIT;
    private Screen pdfReturn = Screen.LIBRARY;
    private ToolShelf activeShelf;
    private int toolsScrollY;
    private Screen toolsReturn = Screen.LIBRARY;
    private ScanDocument.Folder folder = ScanDocument.Folder.NONE;
    private Screen editorReturn = Screen.LIBRARY;
    private ScanRecognition.WordDraft wordDraft;
    private String wordReviewKey = ScanRecognition.WORD;
    private boolean handwritingOriginal = true;
    private ScanRecognition.TableDraft tableDraft;
    private String tableReviewKey = ScanRecognition.EXCEL;
    private TableGridView tableGrid;
    private Button pendingCells;
    private int wordPage;
    private int tableIndex;
    private boolean wordDirty;
    private String pendingFile;
    private String pendingName;
    private FileFormat pendingFormat;
    private ActivityResultLauncher<Intent> gallery;
    private ActivityResultLauncher<Intent> camera;
    private ActivityResultLauncher<Intent> pdfPicker;
    private ActivityResultLauncher<Intent> outputPicker;
    private final OnBackPressedCallback back = new OnBackPressedCallback(false) {
        @Override public void handleOnBackPressed() { navigateBack(); }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new ScanUi(this);
        store = new ScanStore(this);
        pdfStore = new PdfWorkspace(this);
        PdfTools.initialize(getApplicationContext());
        preferences = getSharedPreferences("workspace", MODE_PRIVATE);
        gallery = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                result -> receiveExternal(PICK_IMAGES, result.getResultCode(), result.getData()));
        camera = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                result -> receiveExternal(TAKE_IMAGE, result.getResultCode(), result.getData()));
        pdfPicker = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                result -> receivePdf(result.getResultCode(), result.getData()));
        outputPicker = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                result -> receiveExternal(SAVE_FILE, result.getResultCode(), result.getData()));
        getOnBackPressedDispatcher().addCallback(this, back);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightStatusBars(true);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightNavigationBars(true);
        if (Build.VERSION.SDK_INT >= 29) getWindow().setNavigationBarContrastEnforced(true);
        root = new FrameLayout(this);
        root.setBackgroundColor(ScanUi.WHITE);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            androidx.core.graphics.Insets safe = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return insets;
        });
        setContentView(root);
        restore(state);
        render();
    }

    private void restore(Bundle state) {
        try {
            String openId = preferences.getString("document", null);
            if (openId != null) document = store.load(openId);
            if (document != null) {
                screen = Screen.EDITOR;
                selectedPage = preferences.getString("page", null);
                cropping = preferences.getBoolean("crop", false);
            }
            if (state == null) return;
            pendingFile = state.getString("export");
            pendingName = state.getString("exportName");
            String format = state.getString("exportFormat");
            if (format != null) pendingFormat = FileFormat.valueOf(format);
            String lastScreen = state.getString("screen");
            searchInput = state.getString("searchInput", "");
            searchQuery = state.getString("searchQuery", "");
            nextImageTool = ImageTool.valueOf(state.getString("imageTool", ImageTool.EDIT.name()));
            nextPdfTool = PdfTool.valueOf(state.getString("pdfTool", PdfTool.OPEN.name()));
            toolsScrollY = state.getInt("toolsScrollY");
            toolsReturn = Screen.valueOf(state.getString("toolsReturn", Screen.LIBRARY.name()));
            editorReturn = Screen.valueOf(state.getString("editorReturn", Screen.LIBRARY.name()));
            folder = ScanDocument.Folder.valueOf(state.getString("folder", ScanDocument.Folder.NONE.name()));
            if (Screen.PDF.name().equals(lastScreen)) {
                pdfReturn = Screen.valueOf(state.getString("pdfReturn", Screen.LIBRARY.name()));
                pdfPages = pdfStore.load();
                pdfLoaded = true;
                pdfPage = state.getInt("pdfPage");
                screen = Screen.PDF;
                return;
            }
            if (Screen.TOOLS.name().equals(lastScreen) || Screen.FOLDER.name().equals(lastScreen)
                    || Screen.SEARCH.name().equals(lastScreen)) {
                screen = Screen.valueOf(lastScreen);
                return;
            }
            if (document == null) return;
            pendingCrop = CropQuad.from(state.getFloatArray("pendingCrop"));
            if (Screen.WORD.name().equals(lastScreen)) {
                wordReviewKey = state.getBoolean("handwriting") ? ScanRecognition.HANDWRITING : ScanRecognition.WORD;
                handwritingOriginal = state.getBoolean("handwritingOriginal", true);
                org.json.JSONObject draft = store.loadReview(document, wordReviewKey);
                if (draft != null) {
                    wordDraft = ScanRecognition.WordDraft.from(draft);
                    wordPage = state.getInt("wordPage");
                    screen = Screen.WORD;
                }
            } else if (Screen.EXCEL.name().equals(lastScreen)) {
                tableReviewKey = state.getBoolean("handTable") ? ScanRecognition.HAND_TABLE : ScanRecognition.EXCEL;
                org.json.JSONObject draft = store.loadReview(document, tableReviewKey);
                if (draft != null) {
                    tableDraft = ScanRecognition.TableDraft.from(draft);
                    tableIndex = state.getInt("tableIndex");
                    screen = Screen.EXCEL;
                }
            }
        } catch (Exception unreadable) {
            toast("上次的视图无法恢复，可以重新打开扫描件");
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("screen", screen.name());
        state.putString("searchInput", searchView == null ? searchInput : searchView.input.getText().toString());
        state.putString("searchQuery", searchQuery);
        state.putString("imageTool", nextImageTool.name());
        state.putString("pdfTool", nextPdfTool.name());
        state.putString("pdfReturn", pdfReturn.name());
        state.putInt("toolsScrollY", activeShelf == null ? toolsScrollY : activeShelf.offset());
        state.putString("toolsReturn", toolsReturn.name());
        state.putString("editorReturn", editorReturn.name());
        state.putString("folder", folder.name());
        state.putInt("pdfPage", pdfPage);
        state.putString("export", pendingFile);
        state.putString("exportName", pendingName);
        state.putString("exportFormat", pendingFormat == null ? null : pendingFormat.name());
        state.putFloatArray("pendingCrop", pendingCrop == null ? null : pendingCrop.values());
        state.putInt("wordPage", wordPage);
        state.putBoolean("handwriting", ScanRecognition.HANDWRITING.equals(wordReviewKey));
        state.putBoolean("handwritingOriginal", handwritingOriginal);
        state.putInt("tableIndex", tableIndex);
        state.putBoolean("handTable", ScanRecognition.HAND_TABLE.equals(tableReviewKey));
    }

    @Override protected void onPause() {
        if (wordDirty) saveWordDraft(false);
        if (job == null && screen == Screen.EDITOR) saveCrop(false);
        remember();
        super.onPause();
    }

    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        render();
    }

    @Override protected void onDestroy() {
        main.removeCallbacks(saveWord);
        if (job != null) {
            job.cancelled.set(true);
            job.dialog.dismiss();
        }
        work.shutdown();
        previews.shutdownNow();
        images.evictAll();
        super.onDestroy();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_RUNNING_LOW) images.trimToSize(5 * 1024 * 1024);
    }

    private void navigateBack() {
        if (job != null) return;
        if (screen == Screen.WORD || screen == Screen.EXCEL) {
            if (screen != Screen.WORD || saveWordDraft(true)) {
                screen = Screen.EDITOR;
                render();
            }
        } else if (screen == Screen.EDITOR) {
            finishEditor();
        } else if (screen == Screen.SEARCH) {
            screen = Screen.LIBRARY;
            render();
        } else if (screen == Screen.PDF || screen == Screen.TOOLS || screen == Screen.FOLDER) {
            screen = screen == Screen.PDF ? pdfReturn : screen == Screen.TOOLS ? toolsReturn : Screen.TOOLS;
            if (screen == Screen.EDITOR && document == null) screen = Screen.LIBRARY;
            render();
        }
    }

    private void render() {
        back.setEnabled(screen != Screen.LIBRARY);
        contentGeneration++;
        editor = null;
        tableGrid = null;
        pendingCells = null;
        if (activeShelf != null) {
            toolsScrollY = activeShelf.offset();
            activeShelf = null;
        }
        if (searchView != null) searchInput = searchView.input.getText().toString();
        searchView = null;
        View view;
        switch (screen) {
            case EDITOR: view = editor(); break;
            case WORD: view = wordEditor(); break;
            case EXCEL: view = tableEditor(); break;
            case PDF: view = pdfEditor(); break;
            case TOOLS: view = tools(); break;
            case FOLDER: view = library(); break;
            case SEARCH: view = searchScreen(); break;
            default: view = library();
        }
        root.removeAllViews();
        root.addView(view, new FrameLayout.LayoutParams(ui.match(), ui.match()));
    }

    private View library() {
        LinearLayout layout = ui.vertical();
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        boolean browsingFolder = screen == Screen.FOLDER;
        int maxWidth = getResources().getConfiguration().screenWidthDp >= 600 ? ui.dp(568) : ui.match();
        layout.addView(browsingFolder ? ui.header(folder.label, this::navigateBack, null, null)
                : libraryHeader(), new LinearLayout.LayoutParams(browsingFolder ? ui.match() : maxWidth, ui.wrap()));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout files = ui.vertical();
        files.setPadding(ui.dp(24), ui.dp(4), ui.dp(24), ui.dp(24));
        scroll.addView(files, new ScrollView.LayoutParams(maxWidth, ui.wrap(), Gravity.CENTER_HORIZONTAL));
        layout.addView(scroll, new LinearLayout.LayoutParams(ui.match(), 0, 1));
        if (!browsingFolder) files.addView(homeActions());

        List<ScanDocument> recent;
        List<String> unreadable = new ArrayList<>();
        try {
            recent = store.list(unreadable);
        } catch (IOException failure) {
            recent = new ArrayList<>();
            toast("无法读取本机扫描件");
        }
        if (browsingFolder) recent.removeIf(item -> item.folder != folder);
        if (!unreadable.isEmpty()) {
            TextView warning = ui.text(unreadable.size() + " 份扫描件暂时无法读取。文件仍保存在本机。",
                    13, ScanUi.SECONDARY);
            warning.setPadding(0, ui.dp(14), 0, ui.dp(6));
            files.addView(warning);
        }

        if (!browsingFolder || !recent.isEmpty()) {
            LinearLayout section = ui.horizontal();
            section.setPadding(0, ui.dp(browsingFolder ? 8 : 19), 0, ui.dp(2));
            TextView heading = ui.heading(browsingFolder ? "扫描件" : "最近扫描", 19);
            heading.setAccessibilityHeading(true);
            section.addView(heading, new LinearLayout.LayoutParams(0, ui.wrap(), 1));
            if (!browsingFolder) {
                Button pdf = ui.quietButton("PDF 工作台", ScanUi.ACCENT, () -> openPdf(PdfTool.OPEN));
                pdf.setTextSize(13);
                pdf.setPadding(ui.dp(7), 0, 0, 0);
                pdf.setContentDescription("打开 PDF 工作台");
                addButtonIcon(pdf, R.drawable.ic_next, ScanUi.ACCENT, true);
                section.addView(pdf);
            }
            files.addView(section);
        }
        if (recent.isEmpty()) {
            TextView empty = ui.text(browsingFolder ? "可在扫描件的更多操作中移动到此分类"
                    : "还没有扫描件", 14, ScanUi.SECONDARY);
            empty.setPadding(0, ui.dp(14), 0, ui.dp(16));
            files.addView(empty);
        } else {
            for (ScanDocument item : recent) {
                files.addView(libraryRow(item), new LinearLayout.LayoutParams(ui.match(), ui.wrap()));
                files.addView(ui.rule());
            }
        }
        return layout;
    }

    private View libraryHeader() {
        LinearLayout bar = ui.horizontal();
        bar.setPadding(ui.dp(24), ui.dp(14), ui.dp(24), ui.dp(13));
        TextView title = ui.display(getString(R.string.app_name), 31);
        title.setAccessibilityHeading(true);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ui.wrap(), 1);
        titleParams.setMarginEnd(ui.dp(8));
        bar.addView(title, titleParams);
        bar.addView(ui.icon(R.drawable.ic_search, "搜索扫描件", () -> {
            searchInput = "";
            searchQuery = "";
            searchResults = null;
            screen = Screen.SEARCH;
            render();
        }), new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        Button tools = ui.quietButton("全部工具", ScanUi.INK, this::showTools);
        tools.setTextSize(13);
        tools.setPadding(ui.dp(7), 0, 0, 0);
        addButtonIcon(tools, R.drawable.ic_tools, ScanUi.SECONDARY, false);
        bar.addView(tools);
        return bar;
    }

    private View homeActions() {
        LinearLayout actions = ui.vertical();
        actions.addView(ui.rule());
        LinearLayout scan = ui.horizontal();
        scan.setMinimumHeight(ui.dp(134));
        scan.setPadding(0, ui.dp(13), ui.dp(4), ui.dp(13));
        scan.setBackground(ui.ripple(0, 8, 0));
        scan.setContentDescription("拍照扫描");
        scan.setFocusable(true);
        scan.setOnClickListener(v -> capture());
        LinearLayout labels = ui.vertical();
        labels.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        labels.addView(ui.heading("拍照扫描", 25));
        TextView detail = ui.text("单页或连拍，自动裁边", 13, ScanUi.SECONDARY);
        detail.setPadding(0, ui.dp(10), 0, 0);
        labels.addView(detail);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, ui.wrap(), 1);
        labelParams.setMarginEnd(ui.dp(10));
        scan.addView(labels, labelParams);
        FrameLayout ring = new FrameLayout(this);
        ring.setBackground(ui.panel(0, 44, ScanUi.ACCENT));
        ImageView camera = new ImageView(this);
        camera.setImageResource(R.drawable.ic_camera);
        camera.setImageTintList(ColorStateList.valueOf(ScanUi.PAPER));
        camera.setScaleType(ImageView.ScaleType.FIT_CENTER);
        camera.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        camera.setPadding(ui.dp(17), ui.dp(17), ui.dp(17), ui.dp(17));
        camera.setBackground(ui.panel(ScanUi.INK, 44, 0));
        ring.addView(camera, new FrameLayout.LayoutParams(ui.dp(62), ui.dp(62), Gravity.CENTER));
        scan.addView(ring, new LinearLayout.LayoutParams(ui.dp(74), ui.dp(74)));
        actions.addView(scan, new LinearLayout.LayoutParams(ui.match(), ui.wrap()));
        actions.addView(ui.rule());

        LinearLayout shortcuts = ui.horizontal();
        shortcuts.addView(homeShortcut(R.drawable.ic_gallery, "相册导入", "从相册导入", this::pick),
                new LinearLayout.LayoutParams(0, ui.wrap(), 1));
        shortcuts.addView(homeDivider());
        shortcuts.addView(homeShortcut(R.drawable.ic_add, "导入 PDF", "导入 PDF", () -> openPdf(PdfTool.ADD)),
                new LinearLayout.LayoutParams(0, ui.wrap(), 1));
        actions.addView(shortcuts, new LinearLayout.LayoutParams(ui.match(), ui.wrap()));
        actions.addView(ui.rule());
        return actions;
    }

    private View homeShortcut(int drawable, String title, String description, Runnable action) {
        LinearLayout item = ui.horizontal();
        item.setGravity(Gravity.CENTER);
        item.setMinimumHeight(ui.dp(54));
        item.setPadding(ui.dp(8), ui.dp(7), ui.dp(8), ui.dp(7));
        item.setBackground(ui.ripple(0, 6, 0));
        item.setContentDescription(description);
        item.setFocusable(true);
        item.setOnClickListener(v -> action.run());

        ImageView icon = new ImageView(this);
        icon.setImageResource(drawable);
        icon.setImageTintList(ColorStateList.valueOf(ScanUi.SECONDARY));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams iconSize = new LinearLayout.LayoutParams(ui.dp(18), ui.dp(18));
        iconSize.setMarginEnd(ui.dp(7));
        item.addView(icon, iconSize);
        TextView label = ui.text(title, 14, ScanUi.INK);
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        item.addView(label, new LinearLayout.LayoutParams(ui.wrap(), ui.wrap()));
        return item;
    }

    private void addButtonIcon(Button button, int source, int tint, boolean trailing) {
        Drawable icon = getDrawable(source).mutate();
        int size = ui.dp(17);
        icon.setBounds(0, 0, size, size);
        icon.setTint(tint);
        button.setCompoundDrawablePadding(ui.dp(6));
        button.setCompoundDrawablesRelative(trailing ? null : icon, null, trailing ? icon : null, null);
    }

    private View homeDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(ScanUi.LINE);
        divider.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(1), ui.dp(21)));
        return divider;
    }

    private View libraryRow(ScanDocument item) {
        LinearLayout row = ui.horizontal();
        row.setPadding(0, ui.dp(12), 0, ui.dp(12));
        ImageView thumb = new ImageView(this);
        thumb.setImageResource(R.drawable.ic_document);
        thumb.setScaleType(ImageView.ScaleType.FIT_CENTER);
        thumb.setBackground(ui.panel(ScanUi.SURFACE, 5, ScanUi.LINE));
        thumb.setClipToOutline(true);
        thumb.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(thumb, new LinearLayout.LayoutParams(ui.dp(43), ui.dp(55)));
        if (!item.pages.isEmpty()) {
            loadImage(item, item.pages.get(0), false, true, bitmap -> {
                if (bitmap != null) thumb.setImageBitmap(bitmap);
            });
        }
        LinearLayout label = ui.vertical();
        label.setPadding(ui.dp(12), ui.dp(6), ui.dp(6), ui.dp(6));
        label.setFocusable(true);
        String displayName = DEFAULT_SCAN_NAME.matcher(item.name).replaceFirst("扫描件 $1/$2 $3:$4");
        TextView name = ui.heading(displayName, 15);
        name.setMaxLines(1);
        name.setEllipsize(TextUtils.TruncateAt.END);
        label.addView(name);
        String date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.CHINA)
                .format(new Date(item.modifiedAt));
        String detail = item.pages.size() + " 页";
        if (!DEFAULT_SCAN_NAME.matcher(item.name).matches()) detail += "  ·  " + date;
        else if (item.folder != ScanDocument.Folder.NONE) detail += "  ·  " + item.folder.label;
        TextView metadata = ui.text(detail, 12, ScanUi.SECONDARY);
        metadata.setPadding(0, ui.dp(5), 0, 0);
        label.addView(metadata);
        label.setContentDescription(item.name + "，" + item.pages.size() + " 页");
        label.setOnClickListener(v -> open(item));
        thumb.setOnClickListener(v -> open(item));
        row.addView(label, new LinearLayout.LayoutParams(0, ui.wrap(), 1));
        row.addView(ui.icon(R.drawable.ic_more, item.name + "的更多操作", () -> documentMenu(item)),
                new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        return row;
    }

    private void documentMenu(ScanDocument item) {
        ScanSheet sheet = new ScanSheet(this, ui, item.name);
        sheet.option(R.drawable.ic_edit, "重命名", null, false, () -> rename(item));
        sheet.option(R.drawable.ic_document, "移动到分类", item.folder.label, false, () -> fileDocument(item));
        sheet.option(R.drawable.ic_delete, "删除扫描件", null, false, () -> new AlertDialog.Builder(this)
                .setTitle("删除扫描件？").setMessage("将删除本应用里的原图及编辑记录。已导出的文件仍可使用。")
                .setNegativeButton("取消", null).setPositiveButton("删除", (dialog, which) -> {
                    try {
                        store.delete(item);
                        render();
                    } catch (IOException failure) {
                        error("无法删除扫描件", null);
                    }
                }).show());
        sheet.show();
    }

    private void fileDocument(ScanDocument item) {
        ScanSheet folders = new ScanSheet(this, ui, "移动到分类");
        for (ScanDocument.Folder destination : ScanDocument.Folder.values()) {
            folders.option(R.drawable.ic_document, destination.label, null, item.folder == destination, () -> {
                try {
                    store.save(item.filed(destination));
                    render();
                } catch (IOException failure) {
                    error("无法更新分类，请检查本机存储", null);
                }
            });
        }
        folders.show();
    }

    private void showTools() {
        toolsScrollY = 0;
        toolsReturn = screen == Screen.EDITOR ? Screen.EDITOR : Screen.LIBRARY;
        screen = Screen.TOOLS;
        render();
    }

    private View tools() {
        ToolShelf shelf = new ToolShelf(this, ui, this::navigateBack);
        shelf.section("扫描", "扫描与识别",
                new ToolShelf.Item("扫描文件", R.drawable.ic_scan, this::capture),
                imageTile("扫描证件", R.drawable.ic_id, ImageTool.ID),
                imageTile("提取文字", R.drawable.ic_text, ImageTool.WORD),
                imageTile("手写识别", R.drawable.ic_edit, ImageTool.HANDWRITING),
                imageTile("提取表格", R.drawable.ic_table, ImageTool.TABLE),
                imageTile("手写转 Excel", R.drawable.ic_table, ImageTool.HAND_TABLE),
                imageTile("书页拆分", R.drawable.ic_crop, ImageTool.BOOK),
                imageTile("拍屏去彩纹", R.drawable.ic_camera, ImageTool.SCREEN),
                imageTile("读二维码", R.drawable.ic_qr, ImageTool.QR));
        shelf.section("PDF", "PDF 工具",
                pdfTile("PDF 阅读", R.drawable.ic_pdf, PdfTool.OPEN),
                pdfTile("PDF 合并", R.drawable.ic_add, PdfTool.MERGE),
                pdfTile("PDF 拆分", R.drawable.ic_crop, PdfTool.PAGES),
                pdfTile("转图片", R.drawable.ic_image, PdfTool.IMAGES),
                pdfTile("转 Word", R.drawable.ic_document, PdfTool.WORD),
                pdfTile("转 Excel", R.drawable.ic_table, PdfTool.EXCEL),
                pdfTile("提取文字", R.drawable.ic_text, PdfTool.TEXT),
                pdfTile("转长图", R.drawable.ic_gallery, PdfTool.LONG),
                pdfTile("PDF 压缩", R.drawable.ic_down, PdfTool.COMPRESS),
                pdfTile("PDF 水印", R.drawable.ic_edit, PdfTool.WATERMARK),
                pdfTile("PDF 手写签名", R.drawable.ic_edit, PdfTool.SIGN));
        shelf.section("图文", "图文编辑",
                imageTile("图像矫正", R.drawable.ic_crop, ImageTool.CROP),
                imageTile("文档增强", R.drawable.ic_enhance, ImageTool.ENHANCE),
                imageTile("等比改尺寸", R.drawable.ic_image, ImageTool.RESIZE),
                imageTile("证件照裁切", R.drawable.ic_camera, ImageTool.PASSPORT),
                imageTile("合并长图", R.drawable.ic_gallery, ImageTool.LONG),
                imageTile("提取线稿", R.drawable.ic_edit, ImageTool.LINE),
                imageTile("图片水印", R.drawable.ic_edit, ImageTool.WATERMARK),
                imageTile("图片手写签名", R.drawable.ic_edit, ImageTool.SIGN),
                imageTile("手动消除", R.drawable.ic_enhance, ImageTool.REPAIR));
        shelf.section("文件", "文件整理",
                folderTile(ScanDocument.Folder.IDS), folderTile(ScanDocument.Folder.ALBUM),
                folderTile(ScanDocument.Folder.MEETINGS), folderTile(ScanDocument.Folder.QUESTIONS),
                folderTile(ScanDocument.Folder.MISTAKES));
        activeShelf = shelf;
        return shelf.view(toolsScrollY);
    }

    private ToolShelf.Item folderTile(ScanDocument.Folder destination) {
        return new ToolShelf.Item(destination.label, R.drawable.ic_document, () -> {
            folder = destination;
            screen = Screen.FOLDER;
            render();
        });
    }

    private ToolShelf.Item pdfTile(String label, int icon, PdfTool action) {
        return new ToolShelf.Item(label, icon, () -> openPdf(action));
    }

    private ToolShelf.Item imageTile(String label, int icon, ImageTool action) {
        return new ToolShelf.Item(label, icon, () -> imageToolPicker(label, action));
    }

    private ScanDocument importTitle(ScanDocument scan, Uri source) {
        return scan.titleFromImage(ScanExport.displayName(getContentResolver(), source, null));
    }

    private void nameFromDraft(ScanRecognition.WordDraft draft) {
        if (document == null) return;
        ScanDocument named = document.titleFromDraft(wordReviewKey, draft.pages);
        if (named != document) saveDocument(named, false);
    }

    private void rename(ScanDocument item) {
        EditText input = new EditText(this);
        input.setSingleLine();
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});
        input.setText(item.name);
        input.selectAll();
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("重命名").setView(inset(input)).setNegativeButton("取消", null)
                .setPositiveButton("保存", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) {
                input.setError("请输入名称");
                return;
            }
            try {
                store.save(item.renamed(name));
                dialog.dismiss();
                render();
            } catch (IOException failure) {
                input.setError("无法保存名称");
            }
        }));
        dialog.show();
    }

    private void open(ScanDocument item) {
        open(item, null);
    }

    private void open(ScanDocument item, String pageId) {
        editorReturn = screen == Screen.FOLDER || screen == Screen.SEARCH || screen == Screen.TOOLS
                ? screen : Screen.LIBRARY;
        document = item;
        selectedPage = pageId != null ? pageId : item.pages.isEmpty() ? null : item.pages.get(0).id;
        cropping = false;
        pendingCrop = null;
        screen = Screen.EDITOR;
        remember();
        render();
    }

    private View searchScreen() {
        searchView = new LibrarySearchView(this, ui, searchInput, this::navigateBack,
                new LibrarySearchView.Actions() {
                    public void search(String query, boolean index) { runSearch(query, index); }
                    public void open(DocumentSearch.Hit hit) {
                        try {
                            ScanDocument item = store.load(hit.documentId);
                            if (item == null) throw new IOException();
                            MainActivity.this.open(item, hit.pageId);
                        } catch (IOException unavailable) {
                            error("无法打开这份扫描件", null);
                        }
                    }
                });
        if (!searchInput.trim().equals(searchQuery)) return searchView;
        if (searchResults != null) searchView.display(searchQuery, searchResults);
        else if (!searchQuery.isEmpty()) {
            LibrarySearchView visible = searchView;
            main.post(() -> { if (searchView == visible && job == null) runSearch(searchQuery, false); });
        }
        return searchView;
    }

    private void runSearch(String query, boolean index) {
        task(index ? "在本机识别正文" : "搜索扫描件", true, state ->
                DocumentSearch.find(store, getAssets(), query, index, (done, total) -> {
                    if (index && total > 0) state.progress(done, total);
                    else state.check();
                }), found -> {
            searchQuery = query;
            searchResults = found;
            if (searchView != null) searchView.display(query, found);
        }, () -> runSearch(query, index));
    }

    private EditorView editor() {
        ScanDocument.Page page = currentPage();
        if (page != null && cropping && pendingCrop == null) pendingCrop = page.crop;
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE
                && getResources().getConfiguration().screenWidthDp >= 620;
        ScanDocument current = document;
        editor = new EditorView(this, ui, current, page, pendingCrop, cropping, landscape,
                new EditorView.Controller() {
                    @Override public void action(EditorView.Action action) { handleEditor(action); }
                    @Override public void select(ScanDocument.Page page) { selectPage(page); }
                    @Override public void move(String origin, String target) { movePage(origin, target); }
                    @Override public void cropChanged(CropQuad crop) { pendingCrop = crop; }
                    @Override public void load(ScanDocument.Page page, boolean original, boolean thumb,
                            EditorView.ImageResult result) { loadImage(current, page, original, thumb, result); }
                });
        return editor;
    }

    private void handleEditor(EditorView.Action action) {
        if (job != null) return;
        switch (action) {
            case BACK:
            case COMPLETE: finishEditor(); break;
            case EXPORT: if (saveCrop(true)) showExportFormats(); break;
            case AUTO_CROP: automaticCrop(); break;
            case CROP: if (!cropping) startCrop(); break;
            case ROTATE: rotate(); break;
            case ENHANCE: if (saveCrop(true)) filters(); break;
            case RESET:
                pendingCrop = CropQuad.FULL;
                if (editor != null) editor.showCrop(pendingCrop);
                break;
            case PAGE_MENU: pageMenu(); break;
            case ADD: if (saveCrop(true)) addMenu(); break;
        }
    }

    private void startCrop() {
        ScanDocument.Page page = currentPage();
        if (page == null) return;
        cropping = true;
        pendingCrop = page.crop;
        remember();
        render();
    }

    private void automaticCrop() {
        ScanDocument current = document;
        ScanDocument.Page page = currentPage();
        if (page == null) return;
        task("检测文档边缘", false, state -> {
            Bitmap raw = ScanImages.source(store.image(current, page), page.rotation, 1000);
            try {
                return ScanImages.detect(raw);
            } finally {
                raw.recycle();
            }
        }, result -> {
            if (result == null) {
                toast("未找到完整边缘，可以手动拖动四角");
            } else {
                pendingCrop = result;
                if (editor != null) editor.showCrop(result);
            }
        }, this::automaticCrop);
    }

    private boolean saveCrop(boolean report) {
        ScanDocument.Page page = currentPage();
        if (!cropping || page == null || pendingCrop == null || page.crop.equals(pendingCrop)) return true;
        return updatePage(page.crop(pendingCrop), report);
    }

    private void rotate() {
        if (!saveCrop(true)) return;
        ScanDocument.Page page = currentPage();
        if (page == null) return;
        ScanDocument.Page rotated = page.rotate();
        if (updatePage(rotated, true)) {
            if (cropping) pendingCrop = rotated.crop;
            render();
        }
    }

    private void filters() {
        if (currentPage() == null) return;
        ScanSheet sheet = new ScanSheet(this, ui, "画质增强");
        CheckBox all = new CheckBox(this);
        all.setText("应用到全部页");
        all.setButtonTintList(ColorStateList.valueOf(ScanUi.ACCENT));
        all.setPadding(ui.dp(8), 0, ui.dp(8), 0);
        all.setMinHeight(ui.dp(47));
        if (document.pages.size() > 1) sheet.add(all);
        for (ScanDocument.Filter filter : ScanDocument.Filter.values()) {
            sheet.option(0, filter.label, filter.description,
                    currentPage().filter == filter, () -> applyFilter(filter, all.isChecked()));
        }
        sheet.show();
    }

    private void applyFilter(ScanDocument.Filter filter, boolean all) {
        List<ScanDocument.Page> pages = new ArrayList<>();
        ScanDocument.Page current = currentPage();
        boolean changed = false;
        for (ScanDocument.Page page : document.pages) {
            if ((all || page == current) && page.filter != filter) {
                pages.add(page.filter(filter));
                changed = true;
            } else pages.add(page);
        }
        if (changed && !saveDocument(document.withPages(pages), true)) return;
        cropping = false;
        pendingCrop = null;
        remember();
        render();
    }

    private void selectPage(ScanDocument.Page page) {
        if (job != null || !saveCrop(true)) return;
        selectedPage = page.id;
        pendingCrop = null;
        remember();
        render();
    }

    private void movePage(String origin, String destination) {
        if (job != null || origin.equals(destination) || !saveCrop(true)) return;
        int from = indexOf(origin);
        int target = indexOf(destination);
        if (from < 0 || target < 0) return;
        List<ScanDocument.Page> pages = new ArrayList<>(document.pages);
        pages.add(target, pages.remove(from));
        if (saveDocument(document.withPages(pages), true)) render();
    }

    private void pageMenu() {
        ScanDocument.Page current = currentPage();
        if (current == null) return;
        int index = indexOf(current.id);
        ScanSheet sheet = new ScanSheet(this, ui, "第 " + (index + 1) + " 页");
        if (index > 0) sheet.option(R.drawable.ic_up, "向前移动", null, false,
                () -> movePage(current.id, document.pages.get(index - 1).id));
        if (index < document.pages.size() - 1) sheet.option(R.drawable.ic_down, "向后移动", null, false,
                () -> movePage(current.id, document.pages.get(index + 1).id));
        sheet.option(R.drawable.ic_image, "保存这页图片", null, false,
                () -> { if (saveCrop(true)) imageFormats(); });
        sheet.option(R.drawable.ic_edit, "图片工具", null, false, this::imageToolsMenu);
        sheet.option(R.drawable.ic_delete, "移除这页", null, false, () -> new AlertDialog.Builder(this)
                .setTitle("移除第 " + (index + 1) + " 页？")
                .setMessage("本扫描件中的这一页将被移除。")
                .setNegativeButton("取消", null).setPositiveButton("移除", (dialog, which) -> removePage(index))
                .show());
        sheet.show();
    }

    private void removePage(int index) {
        List<ScanDocument.Page> pages = new ArrayList<>(document.pages);
        ScanDocument.Page removed = pages.remove(index);
        if (saveDocument(document.withPages(pages), true)) {
            store.removeSource(document, removed);
            selectedPage = pages.isEmpty() ? null : pages.get(Math.min(pages.size() - 1, index)).id;
            pendingCrop = null;
            if (pages.isEmpty()) cropping = false;
            remember();
            render();
        }
    }

    private void finishEditor() {
        if (!saveCrop(true)) return;
        if (cropping) {
            cropping = false;
            pendingCrop = null;
            remember();
            render();
            return;
        }
        document = null;
        selectedPage = null;
        pendingCrop = null;
        wordDraft = null;
        tableDraft = null;
        screen = editorReturn;
        if (screen == Screen.SEARCH) searchResults = null;
        editorReturn = Screen.LIBRARY;
        remember();
        render();
    }

    private ScanDocument.Page currentPage() {
        if (document == null || document.pages.isEmpty()) return null;
        int index = indexOf(selectedPage);
        return document.pages.get(Math.max(0, index));
    }

    private int indexOf(String pageId) {
        if (document == null) return -1;
        for (int i = 0; i < document.pages.size(); i++) {
            if (document.pages.get(i).id.equals(pageId)) return i;
        }
        return -1;
    }

    private boolean updatePage(ScanDocument.Page replacement, boolean report) {
        int index = indexOf(replacement.id);
        if (index < 0) return false;
        List<ScanDocument.Page> pages = new ArrayList<>(document.pages);
        pages.set(index, replacement);
        return saveDocument(document.withPages(pages), report);
    }

    private boolean saveDocument(ScanDocument changed, boolean report) {
        try {
            store.save(changed);
            document = changed;
            return true;
        } catch (IOException failure) {
            if (report) error("无法保存页面调整，请检查本机存储后重试", null);
            return false;
        }
    }

    private void remember() {
        SharedPreferences.Editor state = preferences.edit();
        if (document == null) {
            state.remove("document").remove("page").remove("crop");
        } else {
            state.putString("document", document.id).putString("page", selectedPage)
                    .putBoolean("crop", cropping);
        }
        state.apply();
    }

    private void loadImage(ScanDocument owner, ScanDocument.Page page, boolean original, boolean thumb,
            EditorView.ImageResult result) {
        String key = owner.id + "/" + page.id + ":" + page.rotation + ":" + original + ":" + thumb;
        if (!original) key += ":" + page.crop.hashCode() + ":" + page.filter.name();
        final String cacheKey = key;
        Bitmap cached = images.get(cacheKey);
        if (cached != null) {
            result.loaded(cached);
            return;
        }
        int generation = contentGeneration;
        previews.execute(() -> {
            if (generation != contentGeneration || Thread.currentThread().isInterrupted()) return;
            Bitmap loaded;
            try {
                loaded = original ? ScanImages.source(store.image(owner, page), page.rotation, 1440)
                        : ScanImages.render(store.image(owner, page), page, thumb ? 220 : 1440);
                images.put(cacheKey, loaded);
            } catch (Exception failure) {
                loaded = null;
            }
            Bitmap bitmap = loaded;
            main.post(() -> {
                if (generation == contentGeneration && !isDestroyed()) result.loaded(bitmap);
            });
        });
    }

    private void addMenu() {
        ScanSheet sheet = new ScanSheet(this, ui, "添加页面");
        sheet.option(R.drawable.ic_camera, "拍照扫描", null, false, this::capture);
        sheet.option(R.drawable.ic_gallery, "从相册导入", "可一次选择多张", false, this::pick);
        sheet.show();
    }

    private void pick() {
        int remaining = ScanStore.PAGE_LIMIT - (screen == Screen.EDITOR && document != null
                ? document.pages.size() : 0);
        if (nextImageTool == ImageTool.ID) remaining = Math.min(remaining, 2);
        if (remaining <= 0) {
            toast("一份扫描件最多 " + ScanStore.PAGE_LIMIT + " 页");
            return;
        }
        if (Build.VERSION.SDK_INT >= 33) {
            Intent photos = new Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*");
            if (remaining > 1) photos.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX,
                    Math.min(remaining, MediaStore.getPickImagesMaxLimit()));
            if (Build.VERSION.SDK_INT >= 35) photos.putExtra(MediaStore.EXTRA_PICK_IMAGES_IN_ORDER, true);
            try {
                gallery.launch(photos);
                return;
            } catch (ActivityNotFoundException ignored) {
                // Android without a system photo picker can open images through DocumentsUI.
            }
        }
        Intent files = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, remaining > 1);
        try {
            gallery.launch(files);
        } catch (ActivityNotFoundException unavailable) {
            error("此设备没有可用的图片选择器", null);
        }
    }

    private void capture() {
        int remaining = ScanStore.PAGE_LIMIT - (screen == Screen.EDITOR && document != null
                ? document.pages.size() : 0);
        if (remaining <= 0) {
            toast("一份扫描件最多 " + ScanStore.PAGE_LIMIT + " 页");
            return;
        }
        boolean id = nextImageTool == ImageTool.ID;
        camera.launch(new Intent(this, CaptureActivity.class)
                .putExtra(CaptureActivity.EXTRA_LIMIT, id ? Math.min(2, remaining) : remaining)
                .putExtra(CaptureActivity.EXTRA_MULTIPLE, id));
    }

    private Uri uri(File file) {
        return FileProvider.getUriForFile(this, getPackageName() + ".files", file);
    }

    private void receiveExternal(int request, int result, Intent data) {
        if (request == SAVE_FILE) {
            exportResult(result, data);
            return;
        }
        if (request == TAKE_IMAGE) {
            receiveCamera(result, data);
            return;
        }
        if (request == PICK_IMAGES) {
            List<Uri> chosen = result == RESULT_OK ? pickedUris(data) : new ArrayList<>();
            if (!chosen.isEmpty()) importPhotos(chosen, new ArrayList<>());
            else nextImageTool = ImageTool.EDIT;
        }
    }

    private void receiveCamera(int result, Intent data) {
        if (result != RESULT_OK || data == null) {
            nextImageTool = ImageTool.EDIT;
            return;
        }
        String shortcut = data.getStringExtra(CaptureActivity.EXTRA_DESTINATION);
        if (CaptureActivity.DEST_ALBUM.equals(shortcut)) {
            pick();
            return;
        }
        if (CaptureActivity.DEST_PDF.equals(shortcut) || CaptureActivity.DEST_TOOLS.equals(shortcut)) {
            nextImageTool = ImageTool.EDIT;
            if (CaptureActivity.DEST_PDF.equals(shortcut)) openPdf(PdfTool.ADD);
            else showTools();
            return;
        }
        List<String> names = data.getStringArrayListExtra(CaptureActivity.EXTRA_FILES);
        List<File> photos = new ArrayList<>();
        List<Uri> inputs = new ArrayList<>();
        File directory = new File(getFilesDir(), "incoming");
        if (names != null) {
            for (String name : new LinkedHashSet<>(names)) {
                if (name == null || !name.endsWith(".jpg")
                        || !ScanStore.validId(name.substring(0, name.length() - 4))) continue;
                File file = new File(directory, name);
                if (!file.isFile()) continue;
                photos.add(file);
                inputs.add(uri(file));
            }
        }
        if (!inputs.isEmpty()) importPhotos(inputs, photos);
        else {
            nextImageTool = ImageTool.EDIT;
            error("相机没有返回完整图片，请重拍", this::capture);
        }
    }

    private static List<Uri> pickedUris(Intent data) {
        Set<Uri> picked = new LinkedHashSet<>();
        if (data != null) {
            ClipData items = data.getClipData();
            if (items != null) {
                for (int i = 0; i < items.getItemCount(); i++) {
                    Uri item = items.getItemAt(i).getUri();
                    if (item != null) picked.add(item);
                }
            } else if (data.getData() != null) picked.add(data.getData());
        }
        return new ArrayList<>(picked);
    }

    private void importPhotos(List<Uri> inputs, List<File> captured) {
        ImageTool action = nextImageTool;
        ScanDocument.Filter initialFilter = switch (action) {
            case SCREEN -> ScanDocument.Filter.SCREEN;
            case EDIT, ENHANCE, WORD, TABLE, CROP, BOOK -> ScanDocument.Filter.AUTO;
            default -> ScanDocument.Filter.ORIGINAL;
        };
        nextImageTool = ImageTool.EDIT;
        boolean append = screen == Screen.EDITOR && document != null;
        Screen returnScreen = screen;
        ScanDocument fresh = append ? document : store.newDocument();
        ScanDocument destination = !append && action == ImageTool.ID
                ? fresh.filed(ScanDocument.Folder.IDS) : fresh;
        int room = ScanStore.PAGE_LIMIT - destination.pages.size();
        if (inputs.size() > room) {
            for (File file : captured) file.delete();
            error("只能再加入 " + room + " 页，请减少所选图片张数", null);
            return;
        }
        task("导入图片", true, state -> {
            List<ScanDocument.Page> pages = new ArrayList<>(destination.pages);
            List<ScanDocument.Page> added = new ArrayList<>();
            List<Integer> failed = new ArrayList<>();
            Uri firstImported = null;
            boolean saved = false;
            try {
                for (int i = 0; i < inputs.size(); i++) {
                    state.check();
                    ScanDocument.Page page = new ScanDocument.Page(ScanStore.newId(), 0,
                            CropQuad.FULL, initialFilter);
                    Bitmap bitmap = null;
                    boolean kept = false;
                    try {
                        store.importImage(getContentResolver(), inputs.get(i), destination, page);
                        bitmap = ScanImages.source(store.image(destination, page), 0, 1000);
                        CropQuad proposal = null;
                        try {
                            proposal = ScanImages.detect(bitmap);
                        } catch (IOException | RuntimeException unavailable) {
                            // Keep the page usable if the native vision component is unavailable.
                            if (initialFilter != ScanDocument.Filter.SCREEN) {
                                page = page.filter(ScanDocument.Filter.ORIGINAL);
                            }
                        }
                        if (proposal != null) page = page.crop(proposal);
                        if (action == ImageTool.HANDWRITING || action == ImageTool.HAND_TABLE) {
                            page = page.filter(ScanImages.initialHandwritingFilter(bitmap, page.crop));
                        }
                        added.add(page);
                        pages.add(page);
                        kept = true;
                        if (firstImported == null) firstImported = inputs.get(i);
                    } catch (IOException | RuntimeException invalid) {
                        failed.add(i + 1);
                    } finally {
                        if (!kept) store.removeSource(destination, page);
                        if (bitmap != null) bitmap.recycle();
                    }
                    state.progress(i + 1, inputs.size());
                }
                if (added.isEmpty()) throw new IOException("没有图片成功导入，请检查文件格式或访问权限");
                ScanDocument merged = destination.withPages(pages);
                if (!append && captured.isEmpty()) merged = importTitle(merged, firstImported);
                state.check();
                store.save(merged);
                saved = true;
                return new ImportResult(merged, added.get(0).id, failed);
            } finally {
                if (!saved) for (ScanDocument.Page page : added) store.removeSource(destination, page);
                for (File file : captured) file.delete();
            }
        }, imported -> {
            document = imported.document;
            if (!append) editorReturn = returnScreen == Screen.TOOLS ? Screen.TOOLS : Screen.LIBRARY;
            selectedPage = imported.first;
            cropping = action == ImageTool.CROP;
            pendingCrop = null;
            screen = Screen.EDITOR;
            remember();
            render();
            if (!imported.failed.isEmpty()) {
                String failed = "以下所选图片无法导入：" + joined(imported.failed);
                if (action == ImageTool.EDIT) error(failed, null);
                else new AlertDialog.Builder(this).setMessage(failed)
                        .setNegativeButton("留在编辑页", null)
                        .setPositiveButton("继续", (dialog, which) -> runImageTool(action)).show();
            } else runImageTool(action);
        }, null);
    }

    private void openPdf(PdfTool action) {
        if (screen != Screen.PDF) pdfReturn = screen;
        if (pdfLoaded) {
            showPdf(action);
            return;
        }
        task("打开 PDF 工作台", false, state -> {
            List<PdfWorkspace.Page> saved = pdfStore.load();
            pdfStore.removeUnused(saved);
            return saved;
        }, saved -> {
            pdfPages = saved;
            pdfLoaded = true;
            pdfPage = preferences.getInt("pdfPosition", 0);
            showPdf(action);
        }, () -> openPdf(action));
    }

    private void showPdf(PdfTool action) {
        screen = Screen.PDF;
        render();
        if (action == PdfTool.OPEN) return;
        if (action == PdfTool.ADD) pickPdf(PdfTool.OPEN);
        else if (pdfPages.isEmpty()) pickPdf(action);
        else choosePdfSource(action);
    }

    private void choosePdfSource(PdfTool action) {
        ScanSheet sheet = new ScanSheet(this, ui, "选择 PDF 文件");
        int count = selectedPdfPages().size();
        sheet.option(R.drawable.ic_pdf, "使用工作台", "当前已选 " + count + " 页",
                false, () -> runPdfTool(action));
        String detail = action == PdfTool.MERGE ? "添加到工作台后选择和排序" : "导入并选中新文件的页面";
        sheet.option(R.drawable.ic_add, "导入 PDF", detail, false, () -> pickPdf(action));
        sheet.show();
    }

    private void pickPdf(PdfTool afterImport) {
        if (pdfPages.size() >= PdfWorkspace.PAGE_LIMIT) {
            error("工作台已有 200 页，请移除部分文件再导入", null);
            return;
        }
        nextPdfTool = afterImport;
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/pdf").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            pdfPicker.launch(pick);
        } catch (ActivityNotFoundException failure) {
            nextPdfTool = PdfTool.OPEN;
            error("此设备没有可用的 PDF 文件选择器", null);
        }
    }

    private void receivePdf(int result, Intent data) {
        PdfTool next = nextPdfTool;
        nextPdfTool = PdfTool.OPEN;
        List<Uri> files = result == RESULT_OK ? pickedUris(data) : new ArrayList<>();
        if (files.isEmpty()) return;
        List<PdfWorkspace.Page> before = pdfPages;
        boolean selectOnlyNew = next != PdfTool.OPEN && next != PdfTool.MERGE;
        task("导入 PDF", true,
                state -> pdfStore.add(getContentResolver(), before, files, selectOnlyNew, state::check,
                        (done, total) -> state.report(done, total, R.string.pdf_import_progress)),
                imported -> {
                    pdfPages = imported;
                    undoPdfPages = null;
                    pdfPage = before.size();
                    rememberPdfPosition();
                    screen = Screen.PDF;
                    render();
                    runPdfTool(next);
                }, () -> pickPdf(next));
    }

    private View pdfEditor() {
        LinearLayout layout = ui.vertical();
        layout.addView(ui.header("PDF 工作台", this::navigateBack,
                pdfPages.isEmpty() ? "导入 PDF" : "导出",
                pdfPages.isEmpty() ? () -> pickPdf(PdfTool.OPEN) : this::pdfExportMenu));
        layout.addView(ui.rule());
        if (pdfPages.isEmpty()) {
            LinearLayout empty = ui.vertical();
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(ui.dp(24), 0, ui.dp(24), 0);
            TextView label = ui.heading("打开已有的 PDF", 22);
            empty.addView(label);
            TextView hint = ui.text("可选多份文件，预览、合并或选页导出", 14, ScanUi.SECONDARY);
            hint.setGravity(Gravity.CENTER);
            hint.setPadding(0, ui.dp(10), 0, 0);
            empty.addView(hint);
            layout.addView(empty, new LinearLayout.LayoutParams(ui.match(), 0, 1));
            return layout;
        }
        pdfPage = Math.max(0, Math.min(pdfPage, pdfPages.size() - 1));
        PdfWorkspace.Page page = pdfPages.get(pdfPage);
        FrameLayout board = new FrameLayout(this);
        ScanPreview preview = new ScanPreview(this, ui);
        board.addView(preview, new FrameLayout.LayoutParams(ui.match(), ui.match()));
        TextView status = ui.text("正在打开页面…", 14, ScanUi.SECONDARY);
        status.setGravity(Gravity.CENTER);
        board.addView(status, new FrameLayout.LayoutParams(ui.match(), ui.match()));
        loadPdfPreview(page, preview, status);

        LinearLayout controls = ui.vertical();
        TextView file = ui.heading(page.name, 15);
        file.setPadding(ui.dp(16), ui.dp(9), ui.dp(16), 0);
        file.setMaxLines(1);
        file.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        controls.addView(file, new LinearLayout.LayoutParams(ui.match(), ui.wrap()));
        TextView selection = ui.text("原第 " + (page.index + 1) + " 页 · 已选 " + selectedPdfPages().size()
                + " / " + pdfPages.size() + " 页", 13, ScanUi.SECONDARY);
        selection.setPadding(ui.dp(16), ui.dp(5), ui.dp(16), 0);
        controls.addView(selection, new LinearLayout.LayoutParams(ui.match(), ui.wrap()));
        LinearLayout pagination = ui.horizontal();
        pagination.setPadding(ui.dp(8), 0, ui.dp(8), 0);
        addPdfNav(pagination, -1, "上一页", R.drawable.ic_previous);
        TextView position = ui.heading("第 " + (pdfPage + 1) + " / " + pdfPages.size() + " 页", 14);
        position.setGravity(Gravity.CENTER);
        position.setOnClickListener(v -> managePdfPages());
        position.setMinHeight(ui.dp(48));
        pagination.addView(position, new LinearLayout.LayoutParams(0, ui.wrap(), 1));
        addPdfNav(pagination, 1, "下一页", R.drawable.ic_next);
        pagination.addView(ui.icon(R.drawable.ic_rotate, "旋转 PDF 当前页", this::rotatePdfPage),
                new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        pagination.addView(ui.icon(R.drawable.ic_more, "PDF 文件操作", this::pdfFileActions),
                new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        controls.addView(pagination);
        controls.addView(ui.rule());
        LinearLayout footer = ui.horizontal();
        footer.setPadding(ui.dp(16), ui.dp(9), ui.dp(16), ui.dp(11));
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, ui.wrap(), 1);
        left.setMarginEnd(ui.dp(8));
        footer.addView(ui.button("添加 PDF", false, () -> pickPdf(PdfTool.OPEN)), left);
        footer.addView(ui.button("选页与排序", false, this::managePdfPages),
                new LinearLayout.LayoutParams(0, ui.wrap(), 1));
        controls.addView(footer);
        Configuration config = getResources().getConfiguration();
        if (config.orientation == Configuration.ORIENTATION_LANDSCAPE && config.screenWidthDp >= 600) {
            LinearLayout split = ui.horizontal();
            split.setGravity(Gravity.TOP);
            split.addView(board, new LinearLayout.LayoutParams(0, ui.match(), 1));
            ScrollView side = new ScrollView(this);
            side.addView(controls);
            split.addView(side, new LinearLayout.LayoutParams(ui.dp(275), ui.match()));
            layout.addView(split, new LinearLayout.LayoutParams(ui.match(), 0, 1));
        } else {
            layout.addView(board, new LinearLayout.LayoutParams(ui.match(), 0, 1));
            layout.addView(controls);
        }
        return layout;
    }

    private void addPdfNav(LinearLayout row, int offset, String label, int icon) {
        boolean enabled = pdfPage + offset >= 0 && pdfPage + offset < pdfPages.size();
        View button = ui.icon(icon, label, () -> {
            pdfPage += offset;
            rememberPdfPosition();
            render();
        });
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : .27f);
        row.addView(button, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
    }

    private void loadPdfPreview(PdfWorkspace.Page page, ScanPreview preview, TextView status) {
        String key = "pdf/" + page.source + ":" + page.index + ":" + page.turns;
        Bitmap cached = images.get(key);
        if (cached != null) {
            status.setVisibility(View.GONE);
            preview.show(cached, null, null);
            return;
        }
        int generation = contentGeneration;
        previews.execute(() -> {
            if (generation != contentGeneration) return;
            Bitmap bitmap = null;
            try {
                bitmap = PdfTools.render(pdfStore, page, 1400);
            } catch (IOException | RuntimeException | OutOfMemoryError unreadable) {
                // The original stays available for page export.
            }
            Bitmap result = bitmap;
            main.post(() -> {
                if (generation != contentGeneration || isDestroyed()) {
                    if (result != null) result.recycle();
                    return;
                }
                if (result == null) status.setText("无法预览此页");
                else {
                    images.put(key, result);
                    status.setVisibility(View.GONE);
                    preview.show(result, null, null);
                }
            });
        });
    }

    private List<PdfWorkspace.Page> selectedPdfPages() {
        List<PdfWorkspace.Page> selected = new ArrayList<>();
        for (PdfWorkspace.Page page : pdfPages) if (page.selected) selected.add(page);
        return selected;
    }

    private void rememberPdfPosition() {
        preferences.edit().putInt("pdfPosition", pdfPage).apply();
    }

    private boolean updatePdfPages(List<PdfWorkspace.Page> pages, int position) {
        List<PdfWorkspace.Page> previous = pdfPages;
        int previousPosition = pdfPage;
        if (!savePdfPages(pages, position)) return false;
        if (!pages.equals(previous)) {
            undoPdfPages = previous;
            undoPdfPage = previousPosition;
        }
        return true;
    }

    private boolean savePdfPages(List<PdfWorkspace.Page> pages, int position) {
        try {
            if (!pages.equals(pdfPages)) pdfStore.save(pages);
            pdfPages = pages;
            pdfPage = Math.max(0, Math.min(position, pages.size() - 1));
            rememberPdfPosition();
            render();
            return true;
        } catch (IOException failure) {
            error("无法保存 PDF 页面调整，请检查本机存储", null);
            return false;
        }
    }

    private void rotatePdfPage() {
        List<PdfWorkspace.Page> pages = new ArrayList<>(pdfPages);
        pages.set(pdfPage, pages.get(pdfPage).rotate());
        updatePdfPages(pages, pdfPage);
    }

    private void managePdfPages() {
        managePdfPages(PdfTool.OPEN);
    }

    private void managePdfPages(PdfTool afterApply) {
        if (pdfPages.isEmpty()) return;
        String action = switch (afterApply) {
            case OPEN -> "应用";
            case SAVE -> "合并并保存";
            case SPLIT -> "拆分并保存";
            default -> "应用并继续";
        };
        PdfPagesDialog.show(this, ui, pdfPages, pdfPage, action, afterApply != PdfTool.OPEN,
                (pages, position) -> {
                    if (!updatePdfPages(pages, position)) return false;
                    runPdfTool(afterApply);
                    return true;
                });
    }

    private void pdfFileActions() {
        ScanSheet sheet = new ScanSheet(this, ui, "PDF 文件操作");
        if (undoPdfPages != null) {
            sheet.option(R.drawable.ic_rotate, "撤销上次页面调整", null, false, () -> {
                if (savePdfPages(undoPdfPages, undoPdfPage)) undoPdfPages = null;
            });
        }
        String source = pdfPages.get(pdfPage).source;
        sheet.option(R.drawable.ic_delete, "移除此文件", null, false, () -> {
            List<PdfWorkspace.Page> kept = new ArrayList<>(pdfPages);
            kept.removeIf(p -> p.source.equals(source));
            confirmPdfRemoval("移除此文件？", kept);
        });
        sheet.option(R.drawable.ic_delete, "清空 PDF 工作台", null, false,
                () -> confirmPdfRemoval("清空 PDF 工作台？", new ArrayList<>()));
        sheet.show();
    }

    private void confirmPdfRemoval(String title, List<PdfWorkspace.Page> pages) {
        new AlertDialog.Builder(this).setTitle(title).setMessage("将清除工作台里的导入副本和相关页序。")
                .setNegativeButton("取消", null)
                .setPositiveButton("移除", (dialog, which) -> task("移除 PDF", false, state -> {
                    pdfStore.save(pages);
                    pdfStore.removeUnused(pages);
                    return pages;
                }, remaining -> {
                    pdfPages = remaining;
                    undoPdfPages = null;
                    pdfPage = Math.min(pdfPage, Math.max(0, pdfPages.size() - 1));
                    rememberPdfPosition();
                    render();
                }, null)).show();
    }

    private void pdfExportMenu() {
        int count = selectedPdfPages().size();
        ScanSheet sheet = new ScanSheet(this, ui, "导出 PDF 工作台");
        sheet.detail("已选 " + count + " 页。PDF 页面副本保留正文和图形。原批注、表单和数字签名请在原文件查看。");
        sheet.option(R.drawable.ic_pdf, "导出所选 PDF", "按当前页序合为一份", false,
                () -> runPdfTool(PdfTool.SAVE));
        sheet.option(R.drawable.ic_crop, "按页拆分", "每页各生成一份 PDF，保存为 ZIP", false,
                () -> runPdfTool(PdfTool.SPLIT));
        sheet.option(R.drawable.ic_document, "转 Word", "按页生成可编辑正文", false,
                () -> runPdfTool(PdfTool.WORD));
        sheet.option(R.drawable.ic_table, "转 Excel", "从所选页识别表格", false,
                () -> runPdfTool(PdfTool.EXCEL));
        sheet.option(R.drawable.ic_image, "转图片", "保存当前页或批量导出", false,
                () -> runPdfTool(PdfTool.IMAGES));
        sheet.option(R.drawable.ic_gallery, "转长图", "按页序纵向拼接", false, () -> runPdfTool(PdfTool.LONG));
        sheet.option(R.drawable.ic_down, "压缩 PDF", "适用于含有大图的文档", false,
                () -> runPdfTool(PdfTool.COMPRESS));
        sheet.option(R.drawable.ic_edit, "加水印", "应用到所选页", false,
                () -> runPdfTool(PdfTool.WATERMARK));
        sheet.option(R.drawable.ic_edit, "手写签名", "放在当前页右下角", false,
                () -> runPdfTool(PdfTool.SIGN));
        sheet.show();
    }

    private void runPdfTool(PdfTool action) {
        if (action == PdfTool.OPEN || pdfPages.isEmpty()) return;
        if (action == PdfTool.PAGES || action == PdfTool.MERGE) {
            managePdfPages(action == PdfTool.MERGE ? PdfTool.SAVE : PdfTool.SPLIT);
            return;
        }
        if (action == PdfTool.IMAGES) {
            pdfImagesMenu();
            return;
        }
        List<PdfWorkspace.Page> picked = selectedPdfPages();
        PdfWorkspace.Page current = pdfPages.get(pdfPage);
        if (picked.isEmpty() && action != PdfTool.PNG) {
            new AlertDialog.Builder(this).setMessage("请选择要处理的页面")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("选页与排序", (dialog, which) -> managePdfPages(action)).show();
            return;
        }
        String title = picked.stream().map(p -> p.source).distinct().count() > 1 ? "合并文档"
                : pdfFileTitle(picked.isEmpty() ? current : picked.get(0));
        switch (action) {
            case SAVE:
                exportPdfCopy(picked, title, null, false);
                break;
            case SPLIT:
                pdfArchive(picked, title + " - 拆分页", false);
                break;
            case COMPRESS:
                exportPdfCopy(picked, title + " - 压缩", null, true);
                break;
            case WORD:
                prepareFile(FileFormat.DOCX, title, (output, state) ->
                        OfficeExport.word(pdfText(picked, state), output));
                break;
            case TEXT:
                task("提取 PDF 文字", true, state -> pdfText(picked, state),
                        words -> textResult("提取文字", String.join("\n\n", words)), null);
                break;
            case EXCEL:
                prepareFile(FileFormat.XLSX, title, (output, state) -> {
                    List<OfficeTable> tables = PdfTools.tables(pdfStore, picked, getAssets(), state::progress);
                    if (tables.isEmpty()) throw new IOException("所选页面未识别出表格");
                    OfficeExport.excel(tables, output);
                });
                break;
            case PNG:
                savePng(pdfFileTitle(current) + " - 第 " + (current.index + 1) + " 页",
                        state -> PdfTools.render(pdfStore, current, ScanImages.EXPORT_EDGE));
                break;
            case LONG:
                savePng(title + " - 长图", state -> LocalImages.stitch(picked.size(),
                        (index, edge) -> PdfTools.render(pdfStore, picked.get(index), edge),
                        state::progress, state::check));
                break;
            case WATERMARK:
                LocalMarkDialog.watermark(this, ui, bitmap ->
                        exportPdfCopy(picked, title + " - 水印", new PdfTools.Stamp(bitmap, true, -1), false));
                break;
            case SIGN:
                int index = picked.indexOf(current);
                if (index < 0) {
                    error("请先勾选当前页，再添加手写签名", this::managePdfPages);
                    return;
                }
                LocalMarkDialog.signature(this, ui, bitmap ->
                        exportPdfCopy(picked, title + " - 签名", new PdfTools.Stamp(bitmap, false, index), false));
                break;
            default:
                break;
        }
    }

    private List<String> pdfText(List<PdfWorkspace.Page> pages, JobState state) throws IOException {
        List<String> words = PdfTools.text(pdfStore, pages, getAssets(), state::progress);
        if (words.stream().allMatch(String::isEmpty)) throw new IOException("所选页面没有识别到文字");
        return words;
    }

    private static String pdfFileTitle(PdfWorkspace.Page page) {
        return page.name.replaceFirst("(?i)\\.pdf$", "");
    }

    private void pdfImagesMenu() {
        ScanSheet sheet = new ScanSheet(this, ui, "PDF 转图片");
        sheet.option(R.drawable.ic_image, "保存当前页 PNG", null, false, () -> runPdfTool(PdfTool.PNG));
        List<PdfWorkspace.Page> pages = selectedPdfPages();
        if (!pages.isEmpty()) {
            sheet.option(R.drawable.ic_gallery, "导出所选页 PNG", pages.size() + " 张图片保存为 ZIP", false,
                    () -> pdfArchive(pages, "PDF 页面图片", true));
        }
        sheet.show();
    }

    private void pdfArchive(List<PdfWorkspace.Page> pages, String title, boolean png) {
        prepareFile(FileFormat.ZIP, title, (stream, state) -> {
            try (ZipOutputStream archive = new ZipOutputStream(stream)) {
                for (int i = 0; i < pages.size(); i++) {
                    state.check();
                    PdfWorkspace.Page page = pages.get(i);
                    String extension = png ? "png" : "pdf";
                    archive.putNextEntry(new ZipEntry(String.format(Locale.ROOT, "page-%03d.%s",
                            i + 1, extension)));
                    if (png) {
                        Bitmap bitmap = PdfTools.render(pdfStore, page, 1800);
                        try {
                            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, archive)) {
                                throw new IOException("无法生成第 " + (i + 1) + " 页图片");
                            }
                        } finally {
                            bitmap.recycle();
                        }
                    } else {
                        PdfTools.write(pdfStore, List.of(page), archive, (done, total) -> state.check());
                    }
                    archive.closeEntry();
                    state.progress(i + 1, pages.size());
                }
            }
        });
    }

    private void exportPdfCopy(List<PdfWorkspace.Page> pages, String title, PdfTools.Stamp stamp,
            boolean compress) {
        prepareFile(FileFormat.PDF, title, (stream, state) ->
                PdfTools.write(pdfStore, pages, stream, state::progress, stamp, compress));
    }

    private void imageToolPicker(String label, ImageTool action) {
        ScanSheet sheet = new ScanSheet(this, ui, label);
        if (document != null && currentPage() != null) {
            sheet.option(R.drawable.ic_document, "使用当前扫描件", document.name, false, () -> {
                screen = Screen.EDITOR;
                render();
                runImageTool(action);
            });
        } else {
            sheet.option(R.drawable.ic_document, "从已有扫描件选择", null, false,
                    () -> chooseScanForTool(label, action));
        }
        sheet.option(R.drawable.ic_camera, "拍照扫描", null, false, () -> {
            nextImageTool = action;
            capture();
        });
        sheet.option(R.drawable.ic_gallery, "从相册导入", null, false, () -> {
            nextImageTool = action;
            pick();
        });
        sheet.show();
    }

    private void chooseScanForTool(String label, ImageTool action) {
        List<ScanDocument> scans;
        try {
            scans = store.list(new ArrayList<>());
        } catch (IOException failure) {
            error("无法读取扫描件", () -> chooseScanForTool(label, action));
            return;
        }
        scans.removeIf(scan -> scan.pages.isEmpty());
        if (scans.isEmpty()) {
            toast("还没有可用的扫描件，请拍照或从相册导入");
            imageToolPicker(label, action);
            return;
        }
        String[] choices = new String[scans.size()];
        for (int i = 0; i < scans.size(); i++) {
            ScanDocument scan = scans.get(i);
            choices[i] = scan.name + "  ·  " + scan.pages.size() + " 页";
        }
        new AlertDialog.Builder(this).setTitle("选择扫描件").setItems(choices, (dialog, index) -> {
            ScanDocument scan = scans.get(index);
            if (scan.pages.size() == 1 || action == ImageTool.WORD || action == ImageTool.HANDWRITING
                    || action == ImageTool.TABLE || action == ImageTool.HAND_TABLE || action == ImageTool.LONG
                    || (action == ImageTool.ID && scan.pages.size() == 2)) {
                open(scan);
                runImageTool(action);
                return;
            }
            String[] pages = new String[scan.pages.size()];
            for (int n = 0; n < pages.length; n++) pages[n] = "第 " + (n + 1) + " 页";
            new AlertDialog.Builder(this).setTitle(action == ImageTool.ID ? "选择证件正面所在页" : "选择要处理的页").setItems(pages, (picker, page) -> {
                open(scan, scan.pages.get(page).id);
                runImageTool(action);
            }).setNegativeButton("返回", (picker, which) -> chooseScanForTool(label, action)).show();
        }).setNegativeButton("返回", (dialog, which) -> imageToolPicker(label, action)).show();
    }

    private void runImageTool(ImageTool action) {
        if (action == ImageTool.EDIT || currentPage() == null) return;
        if (action == ImageTool.CROP) {
            if (!cropping) startCrop();
            return;
        }
        if (!saveCrop(true)) return;
        switch (action) {
            case SCREEN:
                if (cropping || currentPage().filter != ScanDocument.Filter.SCREEN) {
                    applyFilter(ScanDocument.Filter.SCREEN, false);
                }
                break;
            case ENHANCE:
                filters();
                break;
            case WORD:
                recognizeWord(ScanRecognition.WORD);
                break;
            case HANDWRITING:
                recognizeWord(ScanRecognition.HANDWRITING);
                break;
            case TABLE:
                recognizeTables(ScanRecognition.EXCEL);
                break;
            case HAND_TABLE:
                recognizeTables(ScanRecognition.HAND_TABLE);
                break;
            case QR:
                readQr();
                break;
            case RESIZE:
                LocalImageDialog.resize(this, ui, (width, height) -> editImage("改尺寸",
                        image -> LocalImages.resize(image, width, height)));
                break;
            case PASSPORT:
                LocalImageDialog.passport(this, ui, (twoInch, background) -> editImage("证件照",
                        image -> LocalImages.passport(image, twoInch, background)));
                break;
            case LONG:
                scanLongImage();
                break;
            case WATERMARK:
                LocalMarkDialog.watermark(this, ui, mark -> editImage("水印",
                        image -> LocalImages.stamp(image, mark, false)));
                break;
            case SIGN:
                LocalMarkDialog.signature(this, ui, mark -> editImage("签名",
                        image -> LocalImages.stamp(image, mark, true)));
                break;
            case LINE:
                editImage("线稿", LocalImages::lineArt);
                break;
            case ID:
                idSheet();
                break;
            case BOOK:
                splitBook();
                break;
            case REPAIR:
                repairImage();
                break;
            default:
                break;
        }
    }

    private void imageToolsMenu() {
        if (currentPage() == null || !saveCrop(true)) return;
        ScanSheet sheet = new ScanSheet(this, ui, "图片工具");
        sheet.option(R.drawable.ic_image, "等比改尺寸", null, false, () -> runImageTool(ImageTool.RESIZE));
        sheet.option(R.drawable.ic_gallery, "合并长图", null, false, () -> runImageTool(ImageTool.LONG));
        sheet.option(R.drawable.ic_edit, "添加水印", null, false, () -> runImageTool(ImageTool.WATERMARK));
        sheet.option(R.drawable.ic_edit, "手写签名", null, false, () -> runImageTool(ImageTool.SIGN));
        sheet.option(R.drawable.ic_enhance, "手动消除", null, false, () -> runImageTool(ImageTool.REPAIR));
        sheet.option(R.drawable.ic_scan, "读二维码", null, false, () -> runImageTool(ImageTool.QR));
        sheet.show();
    }

    private interface BitmapEdit {
        Bitmap apply(Bitmap bitmap) throws IOException;
    }

    private void editImage(String suffix, BitmapEdit edit) {
        ScanDocument current = document;
        ScanDocument.Page page = currentPage();
        savePng(current.name + " - " + suffix, state -> {
            Bitmap input = ScanImages.render(store.image(current, page), page, ScanImages.EXPORT_EDGE);
            Bitmap output = null;
            try {
                output = edit.apply(input);
                return output;
            } finally {
                if (input != output) input.recycle();
            }
        });
    }

    private void savePng(String title, Job<Bitmap> art) {
        prepareFile(FileFormat.PNG, title, (stream, state) -> {
            Bitmap image = art.run(state);
            try {
                state.check();
                if (!image.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    throw new IOException("无法生成 PNG 图片");
                }
            } finally {
                image.recycle();
            }
        });
    }

    private void scanLongImage() {
        ScanDocument current = document;
        savePng(current.name + " - 长图", state -> LocalImages.stitch(current.pages.size(),
                (index, edge) -> {
                    ScanDocument.Page page = current.pages.get(index);
                    return ScanImages.render(store.image(current, page), page, edge);
                }, state::progress, state::check));
    }

    private void repairImage() {
        ScanDocument current = document;
        ScanDocument.Page page = currentPage();
        task("打开修补预览", false, state -> ScanImages.render(store.image(current, page), page, 1050),
                preview -> LocalRepairDialog.show(this, ui, preview,
                        mask -> editImage("修补", image -> LocalImages.repair(image, mask))), null);
    }

    private void idSheet() {
        ScanDocument current = document;
        int start = Math.max(0, indexOf(selectedPage));
        ScanDocument.Page front = current.pages.get(start);
        ScanDocument.Page back = start + 1 < current.pages.size() ? current.pages.get(start + 1) : null;
        new AlertDialog.Builder(this).setTitle("证件合页")
                .setMessage(back != null ? "将当前页和下一页排进一张 A4，分别作为证件正面和背面。"
                        : "将当前页按比例排进一张 A4。")
                .setNegativeButton("取消", null)
                .setPositiveButton("继续", (dialog, which) ->
                    prepareFile(FileFormat.PDF, current.name + " - 证件合页", (stream, state) -> {
                        Bitmap first = ScanImages.render(store.image(current, front), front, 1350);
                        Bitmap second = null;
                        try {
                            if (back != null) second = ScanImages.render(store.image(current, back), back, 1350);
                            Bitmap sheet = LocalImages.idSheet(first, second);
                            ScanExport.rasterPdf(1, index -> sheet, true, stream, state::progress);
                        } finally {
                            first.recycle();
                            if (second != null) second.recycle();
                        }
                    })).show();
    }

    private void splitBook() {
        ScanDocument current = document;
        ScanDocument.Page page = currentPage();
        new AlertDialog.Builder(this).setTitle("书页拆分")
                .setMessage("从中线分开当前对开页，按左页、右页的顺序导出两页 PDF。")
                .setNegativeButton("取消", null)
                .setPositiveButton("继续", (dialog, which) ->
                    prepareFile(FileFormat.PDF, current.name + " - 对开", (stream, state) -> {
                        Bitmap spread = ScanImages.render(store.image(current, page), page, ScanImages.EXPORT_EDGE);
                        try {
                            ScanExport.rasterPdf(2, index -> LocalImages.splitBook(spread, index == 1),
                                    false, stream, state::progress);
                        } finally {
                            spread.recycle();
                        }
                    })).show();
    }

    private void readQr() {
        ScanDocument current = document;
        ScanDocument.Page page = currentPage();
        task("读取二维码", false, state -> {
            Bitmap image = ScanImages.source(store.image(current, page), page.rotation, 2100);
            try {
                return LocalImages.qr(image);
            } finally {
                image.recycle();
            }
        }, contents -> textResult("二维码内容", contents), null);
    }

    private void textResult(String title, String contents) {
        ScrollView scroll = new ScrollView(this);
        TextView body = ui.text(contents, 15, ScanUi.INK);
        body.setTextIsSelectable(true);
        body.setPadding(ui.dp(22), ui.dp(11), ui.dp(22), ui.dp(11));
        scroll.addView(body);
        LinearLayout container = ui.vertical();
        int height = getResources().getConfiguration().screenHeightDp;
        container.addView(scroll, new LinearLayout.LayoutParams(ui.match(), ui.dp(Math.min(360,
                Math.max(132, height - 220)))));
        new AlertDialog.Builder(this).setTitle(title).setView(container).setNegativeButton("关闭", null)
                .setPositiveButton("复制", (dialog, which) -> copyText(title, contents)).show();
    }

    private void copyText(String title, String text) {
        if (text.trim().isEmpty()) {
            toast("暂无可复制的内容");
            return;
        }
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(title, text));
            toast("已复制");
        }
    }

    private void showExportFormats() {
        if (currentPage() == null) return;
        ScanSheet sheet = new ScanSheet(this, ui, "导出扫描件");
        sheet.option(R.drawable.ic_pdf, "PDF 文档", document.pages.size() + " 页合为一份 PDF",
                false, this::pdfFormats);
        sheet.option(R.drawable.ic_image, "校正图片", "导出当前页为 JPG 或 PNG",
                false, this::imageFormats);
        sheet.option(R.drawable.ic_table, "Excel 表格", "识别表格，校对后保存为 .xlsx",
                false, () -> recognizeTables(ScanRecognition.EXCEL));
        sheet.option(R.drawable.ic_table, "手写转 Excel", "识别手写表格，对照原图逐格校对", false,
                () -> recognizeTables(ScanRecognition.HAND_TABLE));
        sheet.option(R.drawable.ic_document, "Word 文档", "提取文字，校对后保存为 .docx",
                false, () -> recognizeWord(ScanRecognition.WORD));
        sheet.option(R.drawable.ic_edit, "手写转 Word", "识别手写笔记，对照原稿校对", false,
                () -> recognizeWord(ScanRecognition.HANDWRITING));
        sheet.show();
    }

    private void pdfFormats() {
        ScanSheet sheet = new ScanSheet(this, ui, "PDF 页面尺寸");
        sheet.option(R.drawable.ic_pdf, "A4 纸张", "等比适配，方便打印", false, () -> exportPdf(true));
        sheet.option(R.drawable.ic_image, "图片比例", "保留每页的画面比例", false, () -> exportPdf(false));
        sheet.show();
    }

    private void exportPdf(boolean a4) {
        ScanDocument current = document;
        prepareFile(FileFormat.PDF, current.name, (stream, state) ->
                ScanExport.pdf(store, current, a4, stream, state::progress));
    }

    private void imageFormats() {
        ScanSheet sheet = new ScanSheet(this, ui, "保存当前页");
        sheet.option(R.drawable.ic_image, "JPG", "通用格式，文件较小", false, () -> exportImage(false));
        sheet.option(R.drawable.ic_image, "PNG", "无损保存，适合细线与文字", false, () -> exportImage(true));
        sheet.show();
    }

    private void exportImage(boolean png) {
        ScanDocument current = document;
        ScanDocument.Page page = currentPage();
        String name = current.name + " - 第 " + (indexOf(page.id) + 1) + " 页";
        prepareFile(png ? FileFormat.PNG : FileFormat.JPG, name, (stream, state) ->
                ScanExport.image(store, current, page, png, stream));
    }

    private void recognizeWord(String reviewKey) {
        checkDraft(reviewKey, () -> loadWord(reviewKey, false));
    }

    private void loadWord(String reviewKey, boolean rerun) {
        ScanDocument current = document;
        boolean handwriting = ScanRecognition.HANDWRITING.equals(reviewKey);
        task(handwriting ? "识别手写文字" : "识别文字", true, state -> {
            org.json.JSONObject saved = rerun ? null : store.loadReview(current, reviewKey);
            if (saved != null) return ScanRecognition.WordDraft.from(saved);
            ScanRecognition.WordDraft draft = handwriting
                    ? ScanRecognition.handwriting(getAssets(), store, current, state::progress)
                    : ScanRecognition.word(getAssets(), store, current, state::progress);
            if (draft.pages.stream().allMatch(page -> page.trim().isEmpty())
                    && (rerun || store.reviewIsOutdated(current, reviewKey))) {
                throw new IOException("重新识别没有读到文字，已保留原校对稿");
            }
            state.check();
            store.saveReview(current, reviewKey, draft.json());
            return draft;
        }, draft -> {
            wordDraft = draft;
            wordReviewKey = reviewKey;
            nameFromDraft(wordDraft);
            if (!rerun) handwritingOriginal = true;
            wordDirty = false;
            if (!rerun) wordPage = Math.max(0, indexOf(selectedPage));
            screen = Screen.WORD;
            render();
        }, () -> loadWord(reviewKey, rerun));
    }

    private void recognizeTables(String key) {
        checkDraft(key, () -> loadTables(key, false));
    }

    private void loadTables(String key, boolean rerun) {
        ScanDocument current = document;
        boolean handwriting = ScanRecognition.HAND_TABLE.equals(key);
        task(handwriting ? "识别手写表格" : "识别表格", true, state -> {
            org.json.JSONObject saved = rerun ? null : store.loadReview(current, key);
            if (saved != null) return ScanRecognition.TableDraft.from(saved);
            ScanRecognition.TableDraft draft = handwriting
                    ? ScanRecognition.handwrittenTables(getAssets(), store, current, state::progress)
                    : ScanRecognition.tables(getAssets(), store, current, state::progress);
            if (draft.tables.isEmpty()) {
                throw new IOException(handwriting ? "未识别到手写条目。可裁切到填写区域，确认字迹清晰后重试"
                        : "未识别到表格。可先裁切到表格区域、增强清晰度后重试");
            }
            state.check();
            store.saveReview(current, key, draft.json());
            return draft;
        }, draft -> {
            boolean switched = !key.equals(tableReviewKey);
            tableReviewKey = key;
            tableDraft = draft;
            if (!rerun || switched) {
                tableIndex = 0;
                int number = indexOf(selectedPage) + 1;
                for (int i = 0; i < draft.tables.size(); i++) {
                    if (draft.tables.get(i).page == number) {
                        tableIndex = i;
                        break;
                    }
                }
            }
            screen = Screen.EXCEL;
            render();
        }, () -> loadTables(key, rerun));
    }

    private void checkDraft(String type, Runnable recognize) {
        ScanDocument current = document;
        task("检查校对稿", false, state -> store.loadPreviousReview(current, type), previous -> {
            if (previous == null) {
                recognize.run();
                return;
            }
            new AlertDialog.Builder(this).setTitle("扫描页已有调整")
                    .setMessage("可以按原页序导出之前的校对稿。重新识别成功后，新结果将替换旧稿。")
                    .setNegativeButton("取消", null)
                    .setNeutralButton("导出旧稿", (dialog, which) -> {
                        boolean excel = ScanRecognition.EXCEL.equals(type) || ScanRecognition.HAND_TABLE.equals(type);
                        prepareFile(excel ? FileFormat.XLSX : FileFormat.DOCX, current.name + " - 调整前",
                                (stream, export) -> {
                                    if (excel) OfficeExport.excel(ScanRecognition.TableDraft.from(previous).tables, stream);
                                    else OfficeExport.word(ScanRecognition.WordDraft.from(previous).pages, stream);
                                });
                    })
                    .setPositiveButton("重新识别", (dialog, which) -> recognize.run()).show();
        }, null);
    }

    private View wordEditor() {
        LinearLayout layout = ui.vertical();
        layout.setFocusableInTouchMode(true);
        boolean handwriting = ScanRecognition.HANDWRITING.equals(wordReviewKey);
        layout.addView(ui.header(handwriting ? "校对手写稿" : "校对文字", this::navigateBack, "导出 Word", this::exportWord));
        layout.addView(ui.rule());
        wordPage = Math.max(0, Math.min(wordPage, wordDraft.pages.size() - 1));
        final int page = wordPage;
        layout.addView(reviewBar("第 " + (page + 1) + " / " + wordDraft.pages.size() + " 页",
                page > 0 ? () -> turnWordPage(page - 1) : null,
                page + 1 < wordDraft.pages.size() ? () -> turnWordPage(page + 1) : null,
                handwriting ? (handwritingOriginal ? "收起原稿" : "展开原稿") : "看原图", () -> {
                    if (handwriting) {
                        handwritingOriginal = !handwritingOriginal;
                        render();
                    } else showSource(page);
                }, () -> {
                    if (saveWordDraft(true)) loadWord(wordReviewKey, true);
                }, menu -> {
                    if (ScanRecognition.NumberLine.hasDigit(wordDraft.pages.get(page))) {
                        menu.getMenu().add("核对本页数字").setOnMenuItemClickListener(item -> {
                            reviewNumbers(page);
                            return true;
                        });
                    }
                    menu.getMenu().add("复制全文").setOnMenuItemClickListener(item -> {
                        copyText("扫描文字", String.join("\n\n", wordDraft.pages));
                        return true;
                    });
                }));
        if (handwriting && handwritingOriginal) {
            ScanPreview source = new ScanPreview(this, ui);
            layout.addView(source, new LinearLayout.LayoutParams(ui.match(), 0, .72f));
            loadImage(document, document.pages.get(page).filter(ScanDocument.Filter.ORIGINAL),
                    false, false, bitmap -> source.show(bitmap, null, null));
        }
        EditText text = new EditText(this);
        Button edit = ui.quietButton("编辑正文", ScanUi.INK, () -> {
            text.requestFocus();
            WindowCompat.getInsetsController(getWindow(), text).show(WindowInsetsCompat.Type.ime());
        });
        Drawable pencil = getDrawable(R.drawable.ic_edit).mutate();
        pencil.setTint(ScanUi.SECONDARY);
        pencil.setBounds(0, 0, ui.dp(17), ui.dp(17));
        edit.setCompoundDrawablesRelative(pencil, null, null, null);
        edit.setCompoundDrawablePadding(ui.dp(7));
        LinearLayout.LayoutParams editLabel = new LinearLayout.LayoutParams(ui.wrap(), ui.wrap());
        editLabel.setMarginStart(ui.dp(4));
        layout.addView(edit, editLabel);
        text.setTextSize(16);
        text.setTextColor(ScanUi.INK);
        text.setHintTextColor(ScanUi.SECONDARY);
        android.graphics.drawable.StateListDrawable paper = new android.graphics.drawable.StateListDrawable();
        paper.addState(new int[]{android.R.attr.state_focused}, ui.panel(ScanUi.PAPER, 8, ScanUi.ACCENT));
        paper.addState(new int[]{}, ui.panel(ScanUi.PAPER, 8, ScanUi.LINE));
        text.setBackground(paper);
        text.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        text.setGravity(Gravity.TOP | Gravity.START);
        text.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(16));
        text.setLineSpacing(ui.dp(7), 1f);
        text.setHint(handwriting ? "未读出手写文字。可以对照原稿输入，也可裁切到文字区域后重试。"
                : "此页没有识别到文字，可以手动输入");
        text.setContentDescription("第 " + (page + 1) + " 页识别文字，可编辑");
        text.setText(wordDraft.pages.get(page));
        text.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable field) {
                if (screen != Screen.WORD || wordDraft == null) return;
                wordDraft.pages.set(page, field.toString());
                wordDirty = true;
                main.removeCallbacks(saveWord);
                main.postDelayed(saveWord, 450);
            }
        });
        LinearLayout.LayoutParams body = new LinearLayout.LayoutParams(ui.match(), 0, 1);
        body.setMargins(ui.dp(16), 0, ui.dp(16), ui.dp(12));
        layout.addView(text, body);
        layout.requestFocus();
        return layout;
    }

    private void turnWordPage(int page) {
        if (saveWordDraft(true)) {
            wordPage = page;
            render();
        }
    }

    private void reviewNumbers(int position) {
        String text = wordDraft.pages.get(position);
        if (!ScanRecognition.NumberLine.hasDigit(text)) {
            toast("本页没有识别到数字");
            return;
        }
        ScanDocument current = document;
        ScanDocument.Page page = current.pages.get(position);
        List<ScanRecognition.NumberLine> anchors = position < wordDraft.numberLines.size()
                ? wordDraft.numberLines.get(position) : java.util.Collections.emptyList();
        task("读取数字原图", false, state -> ScanImages.renderWord(store.image(current, page),
                page.filter(ScanDocument.Filter.ORIGINAL)), bitmap ->
                NumberReviewDialog.show(this, ui, bitmap, text, anchors, corrected -> {
                    wordDraft.pages.set(position, corrected);
                    wordDirty = true;
                    render();
                    saveWordDraft(true);
                }), null);
    }

    private boolean saveWordDraft(boolean report) {
        if (!wordDirty || wordDraft == null || document == null) return true;
        main.removeCallbacks(saveWord);
        try {
            store.saveReview(document, wordReviewKey, wordDraft.json());
            wordDirty = false;
            nameFromDraft(wordDraft);
            return true;
        } catch (Exception failure) {
            if (report) error("无法保存校对进度，请检查本机存储后重试", () -> saveWordDraft(true));
            return false;
        }
    }

    private void exportWord() {
        if (!saveWordDraft(true)) return;
        List<String> pages = new ArrayList<>(wordDraft.pages);
        boolean hasText = false;
        for (String page : pages) if (!page.trim().isEmpty()) hasText = true;
        if (!hasText) {
            error("还没有可导出的正文，请输入文字或换一张图片", null);
            return;
        }
        prepareFile(FileFormat.DOCX, document.name, (stream, state) -> OfficeExport.word(pages, stream));
    }

    private View tableEditor() {
        LinearLayout layout = ui.vertical();
        boolean handwriting = ScanRecognition.HAND_TABLE.equals(tableReviewKey);
        layout.addView(ui.header(handwriting ? "校对手写表格" : "校对表格", this::navigateBack,
                "导出 Excel", this::exportExcel));
        layout.addView(ui.rule());
        List<OfficeTable> tables = tableDraft.tables;
        tableIndex = Math.max(0, Math.min(tableIndex, tables.size() - 1));
        OfficeTable current = tables.get(tableIndex);
        TableGridView grid = new TableGridView(this, ui, current, this::editCell);
        tableGrid = grid;
        String tableLabel = "第 " + current.page + " 页 · " + (tableIndex + 1) + "/" + tables.size();
        layout.addView(reviewBar(tableLabel,
                tableIndex > 0 ? () -> turnTable(-1) : null,
                tableIndex + 1 < tables.size() ? () -> turnTable(1) : null,
                "看原图", () -> showSource(current.page - 1), () -> loadTables(tableReviewKey, true), menu -> {
                    menu.getMenu().add("恢复默认行列大小").setOnMenuItemClickListener(item -> {
                        grid.resetDimensions();
                        return true;
                    });
                    if (handwriting) {
                        if (current.inferred) menu.getMenu().add("按原图调整分列").setOnMenuItemClickListener(item -> {
                            adjustHandwrittenColumns();
                            return true;
                        });
                        menu.getMenu().add("先核对存疑项（" + uncertainCellCount() + "）")
                                .setEnabled(uncertainCellCount() > 0).setOnMenuItemClickListener(item -> {
                                    nextPendingCell(null, true);
                                    return true;
                                });
                        menu.getMenu().add("逐项核对").setEnabled(unreviewedCount() > 0)
                                .setOnMenuItemClickListener(item -> {
                                    nextPendingCell();
                                    return true;
                                });
                        menu.getMenu().add("打开印刷表格").setOnMenuItemClickListener(item -> {
                            recognizeTables(ScanRecognition.EXCEL);
                            return true;
                        });
                    } else {
                        menu.getMenu().add("按手写重新识别").setOnMenuItemClickListener(item -> {
                            switchToHandTable();
                            return true;
                        });
                    }
                }));
        layout.addView(tableViewportTools(current, grid), new LinearLayout.LayoutParams(ui.match(), ui.wrap()));
        if (!tableDraft.skipped.isEmpty() || current.inferred) {
            String hint = current.inferred ? "已按文字位置推断行列，请依据原图核对。" : "";
            if (!tableDraft.skipped.isEmpty()) hint += "未识别到表格的页码：" + joined(tableDraft.skipped) + "。";
            TextView status = ui.text(hint, 13, ScanUi.SECONDARY);
            status.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(12));
            layout.addView(status);
        }
        layout.addView(ui.rule());
        ScrollView vertical = new ScrollView(this);
        vertical.setFillViewport(true);
        HorizontalScrollView horizontal = new HorizontalScrollView(this);
        horizontal.addView(grid, new HorizontalScrollView.LayoutParams(ui.wrap(), ui.wrap()));
        vertical.addView(horizontal, new ScrollView.LayoutParams(ui.match(), ui.wrap()));
        layout.addView(vertical, new LinearLayout.LayoutParams(ui.match(), 0, 1));
        TextView tip = ui.text(current.rows + " 行 · " + current.columns + " 列" + (handwriting ? "" : " · 点按单元格编辑"),
                13, ScanUi.SECONDARY);
        tip.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12));
        LinearLayout footer = ui.horizontal();
        footer.setBackgroundColor(ScanUi.SURFACE);
        footer.addView(tip, new LinearLayout.LayoutParams(0, ui.wrap(), 1));
        if (handwriting) {
            pendingCells = ui.quietButton("", ScanUi.ACCENT, this::nextPendingCell);
            footer.addView(pendingCells);
            refreshPendingCells();
        }
        layout.addView(footer, new LinearLayout.LayoutParams(ui.match(), ui.wrap()));
        return layout;
    }

    private View tableViewportTools(OfficeTable sheet, TableGridView grid) {
        LinearLayout row = ui.horizontal();
        row.setPadding(ui.dp(16), 0, ui.dp(8), 0);
        TextView hint = ui.text("拖动行号或列标的边界可调整大小", 12, ScanUi.SECONDARY);
        row.addView(hint, new LinearLayout.LayoutParams(0, ui.wrap(), 1));
        Button smaller = ui.quietButton("−", ScanUi.INK, () -> grid.setZoom(grid.getZoom() - .25f));
        Button percent = ui.quietButton("", ScanUi.INK, () -> grid.setZoom(1));
        Button larger = ui.quietButton("+", ScanUi.INK, () -> grid.setZoom(grid.getZoom() + .25f));
        smaller.setContentDescription("缩小表格");
        larger.setContentDescription("放大表格");
        for (Button button : new Button[]{smaller, percent, larger}) {
            button.setPadding(0, 0, 0, 0);
            int width = button == percent ? 64 : 48;
            row.addView(button, new LinearLayout.LayoutParams(ui.dp(width), ui.dp(48)));
        }
        Runnable update = () -> {
            float zoom = grid.getZoom();
            String label = Math.round(zoom * 100) + "%";
            if (!label.contentEquals(percent.getText())) {
                percent.setText(label);
                percent.setContentDescription("缩放 " + label + "，恢复到 100%");
            }
            smaller.setEnabled(zoom > OfficeTable.MIN_ZOOM);
            larger.setEnabled(zoom < OfficeTable.MAX_ZOOM);
            smaller.setAlpha(smaller.isEnabled() ? 1f : .35f);
            larger.setAlpha(larger.isEnabled() ? 1f : .35f);
        };
        update.run();
        grid.addOnLayoutChangeListener((v, l, t, r, b, oldL, oldT, oldR, oldB) -> update.run());

        float[] widths = sheet.columnWidths.clone();
        float[] heights = sheet.rowHeights.clone();
        float[] savedZoom = {sheet.zoom};
        grid.setChangeListener(() -> {
            if (grid != tableGrid || screen != Screen.EXCEL) return;
            try {
                store.saveReview(document, tableReviewKey, tableDraft.json());
                System.arraycopy(sheet.columnWidths, 0, widths, 0, widths.length);
                System.arraycopy(sheet.rowHeights, 0, heights, 0, heights.length);
                savedZoom[0] = sheet.zoom;
            } catch (Exception failure) {
                System.arraycopy(widths, 0, sheet.columnWidths, 0, widths.length);
                System.arraycopy(heights, 0, sheet.rowHeights, 0, heights.length);
                sheet.zoom = savedZoom[0];
                grid.refreshFromModel();
                toast("无法保存表格显示设置，已恢复上次的行列大小和缩放");
            }
            update.run();
        });
        return row;
    }

    private void switchToHandTable() {
        ScanDocument current = document;
        task("检查手写校对稿", false, state -> store.loadReview(current, ScanRecognition.HAND_TABLE) != null, saved -> {
            if (!saved) {
                recognizeTables(ScanRecognition.HAND_TABLE);
                return;
            }
            new AlertDialog.Builder(this).setTitle("已有手写校对稿")
                    .setMessage("可继续校对。重新识别全部页面后，将替换这份手写校对稿。")
                    .setNegativeButton("取消", null)
                    .setNeutralButton("打开已有稿", (d, which) -> loadTables(ScanRecognition.HAND_TABLE, false))
                    .setPositiveButton("重新识别", (d, which) -> loadTables(ScanRecognition.HAND_TABLE, true)).show();
        }, null);
    }

    private void adjustHandwrittenColumns() {
        int index = tableIndex;
        OfficeTable current = tableDraft.tables.get(index);
        LinearLayout panel = ui.vertical();
        TextView sourceLabel = ui.text("对照原图选择列数", 13, ScanUi.SECONDARY);
        panel.addView(sourceLabel);
        ScanPreview original = new ScanPreview(this, ui);
        int screenHeight = getResources().getConfiguration().screenHeightDp;
        panel.addView(original, new LinearLayout.LayoutParams(ui.match(), ui.dp(Math.min(200, screenHeight / 3))));
        LinearLayout choice = ui.horizontal();
        choice.addView(ui.text("列数", 14, ScanUi.SECONDARY), new LinearLayout.LayoutParams(0, ui.dp(48), 1));
        android.widget.Spinner columns = new android.widget.Spinner(this);
        List<String> names = new ArrayList<>();
        names.add("自动按原图分列");
        for (int i = 1; i <= OfficeTable.MAX_COLUMNS; i++) names.add(i + " 列");
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        columns.setAdapter(adapter);
        columns.setSelection(current.columns);
        columns.setContentDescription("分列方式");
        choice.addView(columns, new LinearLayout.LayoutParams(ui.wrap(), ui.dp(48)));
        panel.addView(choice);
        TextView summary = ui.text("", 13, ScanUi.SECONDARY);
        summary.setPadding(0, ui.dp(8), 0, ui.dp(8));
        panel.addView(summary);
        ScrollView vertical = new ScrollView(this);
        HorizontalScrollView preview = new HorizontalScrollView(this);
        vertical.addView(preview);
        panel.addView(vertical, new LinearLayout.LayoutParams(ui.match(), ui.dp(Math.min(160, screenHeight / 4))));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(inset(panel));
        OfficeTable[] arranged = {current};
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("按原图调整分列")
                .setView(scroll).setNegativeButton("取消", null).setPositiveButton("应用分列", null).create();
        columns.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                try {
                    arranged[0] = HandwrittenTableLayout.relayout(current, position);
                    preview.removeAllViews();
                    TableGridView sample = new TableGridView(MainActivity.this, ui, arranged[0], (cell, grid) -> {});
                    sample.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
                    preview.addView(sample);
                    long count = arranged[0].cells.stream().filter(cell -> cell.sourceBounds != null || !cell.text.isEmpty()).count();
                    summary.setText(arranged[0].rows + " 行 · " + arranged[0].columns + " 列 · 保留 " + count + " 项");
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                } catch (IOException | RuntimeException failure) {
                    arranged[0] = null;
                    summary.setText(failure.getMessage());
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (arranged[0] == null) return;
            List<OfficeTable> tables = new ArrayList<>(tableDraft.tables);
            tables.set(index, arranged[0]);
            ScanRecognition.TableDraft updated = new ScanRecognition.TableDraft(tables, tableDraft.skipped);
            try {
                store.saveReview(document, tableReviewKey, updated.json());
            } catch (Exception failure) {
                summary.setText("无法保存分列，请重试");
                return;
            }
            tableDraft = updated;
            dialog.dismiss();
            render();
        }));
        dialog.setOnDismissListener(d -> original.show(null, null, null));
        dialog.show();
        // The same corrected page and normalized coordinates used by cell review.
        loadCellSource(dialog, original, sourceLabel, new OfficeTable.Cell(0, 0, 1, 1, ""));
    }

    private void refreshPendingCells() {
        if (pendingCells == null) return;
        int count = unreviewedCount();
        pendingCells.setEnabled(count > 0);
        pendingCells.setText(count > 0 ? "未核对 " + count + " 格  ›" : "已完成核对");
        pendingCells.setContentDescription(count > 0 ? "逐项核对，" + count + " 格未核对" : "已完成核对");
    }

    private int unreviewedCount() {
        int count = 0;
        for (OfficeTable table : tableDraft.tables) {
            for (OfficeTable.Cell cell : table.cells) if (cell.unreviewed()) count++;
        }
        return count;
    }

    private int uncertainCellCount() {
        int count = 0;
        for (OfficeTable table : tableDraft.tables) {
            for (OfficeTable.Cell cell : table.cells) if (cell.unreviewed() && cell.needsReview) count++;
        }
        return count;
    }

    private void nextPendingCell() {
        nextPendingCell(null, false);
    }

    private void nextPendingCell(OfficeTable.Cell after, boolean uncertainOnly) {
        List<OfficeTable> sheets = tableDraft.tables;
        List<OfficeTable.Cell> ordered = new ArrayList<>();
        for (OfficeTable sheet : sheets) ordered.addAll(sheet.cells);
        int start = after == null ? 0 : ordered.indexOf(after) + 1;
        for (int offset = 0; offset < ordered.size(); offset++) {
            OfficeTable.Cell cell = ordered.get((start + offset) % ordered.size());
            if (!cell.unreviewed() || uncertainOnly && !cell.needsReview) continue;
            for (int index = 0; index < sheets.size(); index++) {
                if (!sheets.get(index).cells.contains(cell)) continue;
                if (tableIndex != index) {
                    tableIndex = index;
                    render();
                }
                tableGrid.reveal(cell);
                editCell(cell, tableGrid, uncertainOnly);
                return;
            }
        }
        if (uncertainOnly && unreviewedCount() > 0) {
            new AlertDialog.Builder(this).setTitle("存疑项已核对")
                    .setMessage("还有 " + unreviewedCount() + " 格未核对，可继续对照原图确认。")
                    .setNegativeButton("返回表格", null)
                    .setPositiveButton("核对其余项", (d, which) -> nextPendingCell()).show();
        }
    }

    private void turnTable(int step) {
        tableIndex += step;
        render();
    }

    private LinearLayout reviewBar(String label, Runnable previous, Runnable next, String sourceLabel, Runnable original,
            Runnable recognizeAgain, java.util.function.Consumer<PopupMenu> extraActions) {
        LinearLayout row = ui.horizontal();
        row.setPadding(ui.dp(8), ui.dp(5), ui.dp(8), ui.dp(5));
        View left = ui.icon(R.drawable.ic_previous, "上一页", previous == null ? () -> {} : previous);
        left.setEnabled(previous != null);
        left.setAlpha(previous == null ? .28f : 1f);
        row.addView(left, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        TextView title = ui.heading(label, 14);
        title.setGravity(Gravity.CENTER);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(title, new LinearLayout.LayoutParams(0, ui.wrap(), 1));
        View right = ui.icon(R.drawable.ic_next, "下一页", next == null ? () -> {} : next);
        right.setEnabled(next != null);
        right.setAlpha(next == null ? .28f : 1f);
        row.addView(right, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        row.addView(ui.button(sourceLabel, false, original), new LinearLayout.LayoutParams(ui.wrap(), ui.wrap()));
        View options = ui.icon(R.drawable.ic_more, "校对操作", () -> {});
        options.setOnClickListener(anchor -> {
            PopupMenu menu = new PopupMenu(this, anchor);
            if (extraActions != null) extraActions.accept(menu);
            menu.getMenu().add("重新识别").setOnMenuItemClickListener(item -> {
                new AlertDialog.Builder(this).setTitle("重新识别？")
                        .setMessage("将重新识别全部页面。成功后会替换当前格式的校对稿。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("重新识别", (dialog, which) -> recognizeAgain.run()).show();
                return true;
            });
            menu.show();
        });
        row.addView(options, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        return row;
    }

    private void showSource(int position) {
        ScanDocument current = document;
        if (position < 0 || position >= current.pages.size()) return;
        ScanDocument.Page page = current.pages.get(position);
        task("读取扫描图片", false, state ->
                ScanImages.source(store.image(current, page), page.rotation, 1500), bitmap -> {
            ScanPreview detail = new ScanPreview(this, ui);
            detail.show(bitmap, null, null);
            int screenHeight = getResources().getConfiguration().screenHeightDp;
            LinearLayout holder = ui.vertical();
            holder.addView(detail, new LinearLayout.LayoutParams(ui.match(), ui.dp(Math.min(520, screenHeight - 150))));
            AlertDialog show = new AlertDialog.Builder(this).setTitle("第 " + (position + 1) + " 页")
                    .setView(holder).setPositiveButton("关闭", null).create();
            show.setOnDismissListener(d -> detail.show(null, null, null));
            show.show();
        }, null);
    }

    private void editCell(OfficeTable.Cell cell, TableGridView grid) {
        editCell(cell, grid, false);
    }

    private void editCell(OfficeTable.Cell cell, TableGridView grid, boolean uncertainOnly) {
        boolean handwriting = ScanRecognition.HAND_TABLE.equals(tableReviewKey);
        LinearLayout panel = ui.vertical();
        ScanPreview original = handwriting ? new ScanPreview(this, ui) : null;
        TextView sourceLabel = handwriting ? ui.text("", 13, ScanUi.SECONDARY) : null;
        if (handwriting) {
            panel.setFocusableInTouchMode(true);
            sourceLabel.setText(cell.unreviewed() && cell.needsReview ? "识别存疑 · 请对照原图" : "核对原图");
            sourceLabel.setPadding(0, 0, 0, ui.dp(6));
            panel.addView(sourceLabel);
            panel.addView(original, new LinearLayout.LayoutParams(ui.match(), ui.dp(
                    Math.min(112, Math.max(64, getResources().getConfiguration().screenHeightDp / 5)))));
        }
        EditText value = new EditText(this);
        value.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        value.setFilters(new InputFilter[]{new InputFilter.LengthFilter(16000)});
        value.setMaxLines(4);
        value.setText(cell.text);
        value.setHint("输入单元格内容");
        value.setSelectAllOnFocus(true);
        panel.addView(value, new LinearLayout.LayoutParams(ui.match(), ui.wrap()));
        if (handwriting && !cell.alternative.isEmpty() && !cell.alternative.equals(cell.text)) {
            Button candidate = ui.quietButton("采用候选  " + cell.alternative, ScanUi.ACCENT, () -> {
                value.setText(cell.alternative);
                value.setSelection(value.length());
            });
            candidate.setMaxLines(2);
            candidate.setEllipsize(TextUtils.TruncateAt.END);
            candidate.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            candidate.setContentDescription("采用候选，" + cell.alternative);
            panel.addView(candidate, new LinearLayout.LayoutParams(ui.match(), ui.wrap()));
        }
        TextView typeLabel = ui.text("单元格类型", 14, ScanUi.SECONDARY);
        typeLabel.setPadding(0, ui.dp(14), 0, ui.dp(8));
        panel.addView(typeLabel);
        android.widget.Spinner type = new android.widget.Spinner(this);
        List<String> choices = new ArrayList<>();
        for (OfficeTable.CellType item : OfficeTable.CellType.values()) choices.add(item.label);
        android.widget.ArrayAdapter<String> names = new android.widget.ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, choices);
        names.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        type.setAdapter(names);
        type.setSelection(cell.type.ordinal());
        type.setContentDescription("单元格类型");
        panel.addView(type, new LinearLayout.LayoutParams(ui.match(), ui.dp(48)));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(inset(panel));
        AlertDialog.Builder options = new AlertDialog.Builder(this)
                .setTitle("编辑 " + OfficeExport.address(cell.row, cell.column))
                .setView(scroll).setNegativeButton("取消", null).setPositiveButton(handwriting ? "确认" : "保存", null);
        int remaining = uncertainOnly ? uncertainCellCount() : unreviewedCount();
        boolean more = handwriting && remaining > (cell.unreviewed() ? 1 : 0);
        if (more) options.setNeutralButton("确认下一项", null);
        AlertDialog dialog = options.create();
        java.util.function.Consumer<Boolean> save = advance -> {
            String updated = value.getText().toString();
            OfficeTable.CellType wanted = OfficeTable.CellType.values()[type.getSelectedItemPosition()];
            if (wanted == OfficeTable.CellType.NUMBER && !updated.trim().isEmpty()
                    && OfficeExport.numeric(updated, true) == null) {
                value.setError("请输入有效数字，最多 15 位有效数字");
                return;
            }
            String previous = cell.text;
            OfficeTable.CellType previousType = cell.type;
            boolean wasReviewed = cell.reviewed;
            cell.text = updated;
            cell.type = wanted;
            if (handwriting) cell.reviewed = true;
            try {
                store.saveReview(document, tableReviewKey, tableDraft.json());
            } catch (Exception failure) {
                cell.text = previous;
                cell.type = previousType;
                cell.reviewed = wasReviewed;
                value.setError("无法保存校对内容");
                return;
            }
            dialog.dismiss();
            TableGridView displayed = tableGrid == null ? grid : tableGrid;
            displayed.cellChanged(cell);
            refreshPendingCells();
            if (advance || uncertainOnly) nextPendingCell(cell, uncertainOnly);
        };
        if (handwriting && cell.reviewed) {
            panel.addView(ui.quietButton("重新标为未核对", ScanUi.ACCENT, () -> {
                cell.reviewed = false;
                try {
                    store.saveReview(document, tableReviewKey, tableDraft.json());
                } catch (Exception failure) {
                    cell.reviewed = true;
                    value.setError("无法保存核对状态");
                    return;
                }
                dialog.dismiss();
                grid.cellChanged(cell);
                refreshPendingCells();
            }));
        }
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> save.accept(false));
            if (more) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> save.accept(true));
        });
        if (handwriting) {
            dialog.setOnDismissListener(d -> original.show(null, null, null));
            dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        }
        dialog.show();
        if (handwriting) loadCellSource(dialog, original, sourceLabel, cell);
    }

    private void loadCellSource(AlertDialog dialog, ScanPreview preview, TextView label, OfficeTable.Cell cell) {
        ScanDocument owner = document;
        int position = tableDraft.tables.get(tableIndex).page - 1;
        if (position < 0 || position >= owner.pages.size()) {
            label.setText("找不到这一页原图");
            preview.setVisibility(View.GONE);
            return;
        }
        ScanDocument.Page page = owner.pages.get(position).filter(ScanDocument.Filter.ORIGINAL);
        String key = owner.id + "/" + page.id + ":cell:" + page.rotation + ":" + page.crop.hashCode();
        previews.execute(() -> {
            Bitmap section = null;
            try {
                Bitmap image = images.get(key);
                if (image == null) {
                    image = ScanImages.renderWord(store.image(owner, page), page);
                    images.put(key, image);
                }
                section = cellImage(image, cell.sourceBounds);
            } catch (IOException | RuntimeException unavailable) {
                // The saved contents can still be edited when a provider image is unavailable.
            } catch (OutOfMemoryError full) {
                images.evictAll();
            }
            Bitmap result = section;
            main.post(() -> {
                if (isDestroyed() || !dialog.isShowing()) return;
                if (result == null) {
                    label.setText("原图暂时无法读取，可继续编辑");
                    preview.setVisibility(View.GONE);
                } else {
                    preview.show(result, null, null);
                    preview.setContentDescription(cell.sourceBounds == null ? "本页原图，可缩放" : "单元格原图局部，可缩放");
                }
            });
        });
    }

    static Bitmap cellImage(Bitmap image, RectF region) {
        if (region == null) return image;
        int w = image.getWidth(), h = image.getHeight();
        int left = Math.max(0, Math.min(w - 1, (int) Math.floor(region.left * w)));
        int top = Math.max(0, Math.min(h - 1, (int) Math.floor(region.top * h)));
        int right = Math.min(w, Math.max(left + 1, (int) Math.ceil(region.right * w)));
        int bottom = Math.min(h, Math.max(top + 1, (int) Math.ceil(region.bottom * h)));
        return Bitmap.createBitmap(image, left, top, right - left, bottom - top);
    }

    private void exportExcel() {
        int pending = ScanRecognition.HAND_TABLE.equals(tableReviewKey) ? unreviewedCount() : 0;
        if (pending > 0) {
            new AlertDialog.Builder(this).setTitle("还有 " + pending + " 格未核对")
                    .setMessage("可先对照原图确认，也可按当前内容生成 Excel。")
                    .setNegativeButton("仍然导出", (d, which) -> writeExcel())
                    .setPositiveButton("继续核对", (d, which) -> nextPendingCell()).show();
        } else writeExcel();
    }

    private void writeExcel() {
        List<OfficeTable> tables = tableDraft.tables;
        prepareFile(FileFormat.XLSX, document.name, (stream, state) -> OfficeExport.excel(tables, stream));
    }

    private void prepareFile(FileFormat format, String title, FileWriter generator) {
        task("生成 " + format.label, true, state -> {
            File directory = exportDirectory();
            ScanStore.ensureDirectory(directory);
            clearOldExports(directory);
            File file = File.createTempFile("scan-", "." + format.extension, directory);
            boolean complete = false;
            try {
                try (OutputStream output = new BufferedOutputStream(new FileOutputStream(file))) {
                    generator.write(output, state);
                }
                state.check();
                if (file.length() == 0) throw new IOException("没有生成可保存的文件");
                complete = true;
                return file;
            } finally {
                if (!complete) file.delete();
            }
        }, file -> {
            pendingFile = file.getName();
            pendingName = fileName(title, format.extension);
            pendingFormat = format;
            launchSave();
        }, () -> prepareFile(format, title, generator));
    }

    private void launchSave() {
        File file = pendingOutput();
        if (file == null) {
            error("临时文件已失效，请重新导出", null);
            return;
        }
        Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(pendingFormat.mime).putExtra(Intent.EXTRA_TITLE, pendingName);
        try {
            outputPicker.launch(save);
        } catch (ActivityNotFoundException unavailable) {
            offerShare(file, pendingName, pendingFormat);
        }
    }

    private void exportResult(int result, Intent data) {
        if (result != RESULT_OK) {
            discardPendingOutput();
            return;
        }
        if (data == null || data.getData() == null) {
            error("系统未返回保存位置，请重新选择", this::launchSave);
            return;
        }
        savePendingOutput(data.getData());
    }

    private void savePendingOutput(Uri destination) {
        File file = pendingOutput();
        if (file == null) {
            error("临时文件已失效，请重新导出", null);
            return;
        }
        String name = pendingName;
        FileFormat format = pendingFormat;
        task("保存 " + format.label, false, state -> {
            try {
                ScanExport.save(getContentResolver(), file, destination);
            } catch (IOException failure) {
                return null;
            }
            return ScanExport.displayName(getContentResolver(), destination, name);
        }, savedName -> {
            if (savedName == null) {
                new AlertDialog.Builder(this).setMessage("未能保存到所选位置")
                        .setNegativeButton("取消保存", (dialog, which) -> discardPendingOutput())
                        .setNeutralButton("另选位置", (dialog, which) -> launchSave())
                        .setPositiveButton("重试", (dialog, which) -> savePendingOutput(destination))
                        .setOnCancelListener(dialog -> discardPendingOutput()).show();
                return;
            }
            clearPendingOutput();
            ScanSheet sheet = new ScanSheet(this, ui, "文件已保存");
            sheet.detail(savedName);
            sheet.option(R.drawable.ic_document, "打开文件", null, false, () -> openOutput(destination, format));
            sheet.option(R.drawable.ic_share, "分享文件", null, false, () -> share(file, savedName, format));
            sheet.option(R.drawable.ic_check, "完成", null, false, () -> {});
            sheet.show();
        }, this::launchSave);
    }

    private void discardPendingOutput() {
        File file = pendingOutput();
        clearPendingOutput();
        if (file != null) file.delete();
    }

    private void clearPendingOutput() {
        pendingFile = null;
        pendingName = null;
        pendingFormat = null;
    }

    private void offerShare(File file, String name, FileFormat format) {
        ScanSheet sheet = new ScanSheet(this, ui, "文件已生成");
        sheet.detail("此设备没有文件保存界面，可以通过分享保存到其他应用。");
        sheet.option(R.drawable.ic_share, "分享 " + format.label, name, false, () -> share(file, name, format));
        sheet.show();
    }

    private void share(File file, String name, FileFormat format) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", file, name);
            Intent send = new Intent(Intent.ACTION_SEND).setType(format.mime).putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            send.setClipData(ClipData.newRawUri(name, uri));
            startActivity(Intent.createChooser(send, "分享文件"));
        } catch (RuntimeException failure) {
            error("无法打开分享界面", null);
        }
    }

    private void openOutput(Uri file, FileFormat format) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(file, format.mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (RuntimeException unavailable) {
            toast("没有可打开此格式的应用，文件已保存");
        }
    }

    private File exportDirectory() {
        return new File(getCacheDir(), "exports");
    }

    private File pendingOutput() {
        if (pendingFile == null || pendingName == null || pendingFormat == null
                || !pendingFile.matches("scan-[a-zA-Z0-9-]+\\.(pdf|jpg|png|docx|xlsx|zip)")) return null;
        File output = new File(exportDirectory(), pendingFile);
        return output.isFile() && output.length() > 0 ? output : null;
    }

    private static void clearOldExports(File directory) {
        long threshold = System.currentTimeMillis() - 24 * 60 * 60 * 1000L;
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) if (file.lastModified() < threshold) file.delete();
        }
    }

    private static String fileName(String title, String suffix) {
        StringBuilder name = new StringBuilder();
        String disallowed = "/\\:*?\"<>|";
        for (int i = 0; i < title.length(); i++) {
            char c = title.charAt(i);
            name.append(Character.isISOControl(c) || disallowed.indexOf(c) >= 0 ? '_' : c);
        }
        String safe = name.toString().trim();
        return (safe.isEmpty() ? "扫描件" : safe) + "." + suffix;
    }

    private enum FileFormat {
        PDF("PDF", "pdf", "application/pdf"),
        JPG("JPG", "jpg", "image/jpeg"),
        PNG("PNG", "png", "image/png"),
        DOCX("Word", "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
        XLSX("Excel", "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
        ZIP("ZIP", "zip", "application/zip");

        final String label;
        final String extension;
        final String mime;

        FileFormat(String label, String extension, String mime) {
            this.label = label;
            this.extension = extension;
            this.mime = mime;
        }
    }

    private interface FileWriter {
        void write(OutputStream stream, JobState state) throws Exception;
    }

    private interface Job<T> {
        T run(JobState state) throws Exception;
    }

    private interface Result<T> {
        void show(T value);
    }

    private <T> void task(String title, boolean cancel, Job<T> operation, Result<T> success, Runnable retry) {
        if (job != null || isFinishing()) return;
        JobState state = new JobState(title, cancel);
        job = state;
        work.execute(() -> {
            try {
                state.check();
                T result = operation.run(state);
                main.post(() -> {
                    if (complete(state)) success.show(result);
                });
            } catch (CancellationException cancelled) {
                main.post(() -> {
                    if (complete(state)) toast("已取消");
                });
            } catch (Exception failure) {
                main.post(() -> {
                    if (complete(state)) error(message(title, failure), retry);
                });
            } catch (OutOfMemoryError memory) {
                images.evictAll();
                main.post(() -> {
                    if (complete(state)) error("内存空间不足，请减少单次处理页数后重试", retry);
                });
            }
        });
    }

    private boolean complete(JobState state) {
        if (job != state || isDestroyed()) return false;
        state.dialog.dismiss();
        job = null;
        return !isFinishing();
    }

    private static String message(String title, Exception failure) {
        String detail = failure.getMessage();
        if (failure instanceof IOException && detail != null && detail.matches("^[\\u3000-\\u9fff].{0,100}$")
                && !detail.contains("/")) return detail;
        return title + "失败，请重试";
    }

    private final class JobState {
        final AtomicBoolean cancelled = new AtomicBoolean();
        final AlertDialog dialog;
        final ProgressBar indicator;
        final TextView position;

        JobState(String title, boolean cancel) {
            LinearLayout body = ui.vertical();
            body.setPadding(ui.dp(23), ui.dp(15), ui.dp(23), ui.dp(14));
            indicator = new ProgressBar(MainActivity.this, null, android.R.attr.progressBarStyleHorizontal);
            indicator.setIndeterminate(true);
            indicator.setProgressTintList(ColorStateList.valueOf(ScanUi.ACCENT));
            indicator.setIndeterminateTintList(ColorStateList.valueOf(ScanUi.ACCENT));
            body.addView(indicator, new LinearLayout.LayoutParams(ui.match(), ui.dp(14)));
            position = ui.text("处理中…", 14, ScanUi.SECONDARY);
            position.setPadding(0, ui.dp(10), 0, 0);
            position.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            body.addView(position);
            AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this).setTitle(title)
                    .setView(body).setCancelable(false);
            if (cancel) builder.setNegativeButton("取消", null);
            dialog = builder.create();
            dialog.show();
            if (cancel) dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(button -> {
                cancelled.set(true);
                position.setText("正在取消…");
                button.setEnabled(false);
            });
        }

        void check() {
            if (cancelled.get()) throw new CancellationException();
        }

        void progress(int done, int total) {
            report(done, total, R.string.page_progress);
        }

        void report(int done, int total, int format) {
            check();
            main.post(() -> {
                if (job == this && !cancelled.get()) {
                    indicator.setIndeterminate(false);
                    indicator.setMax(total);
                    indicator.setProgress(done);
                    position.setText(getString(format, done, total));
                }
            });
        }
    }

    private void error(String text, Runnable retry) {
        if (isFinishing() || isDestroyed()) return;
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setMessage(text);
        if (retry == null) dialog.setPositiveButton("知道了", null);
        else dialog.setPositiveButton("重试", (ignored, which) -> retry.run()).setNegativeButton("关闭", null);
        dialog.show();
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private View inset(View view) {
        LinearLayout container = ui.vertical();
        container.setPadding(ui.dp(22), ui.dp(10), ui.dp(22), ui.dp(6));
        container.addView(view, new LinearLayout.LayoutParams(ui.match(), ui.wrap()));
        return container;
    }

    private static String joined(List<Integer> positions) {
        List<String> numbers = new ArrayList<>();
        for (int position : positions) numbers.add(Integer.toString(position));
        return String.join("、", numbers);
    }

    private static final class ImportResult {
        final ScanDocument document;
        final String first;
        final List<Integer> failed;

        ImportResult(ScanDocument document, String first, List<Integer> failed) {
            this.document = document;
            this.first = first;
            this.failed = failed;
        }
    }
}
