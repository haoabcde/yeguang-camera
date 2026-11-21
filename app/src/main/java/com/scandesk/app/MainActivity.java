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
import java.text.SimpleDateFormat;
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
    private enum Screen { LIBRARY, FILES, DETAIL, EDITOR, WORD, EXCEL, TOOLS, PDF, FOLDER, SEARCH }
    private enum PdfTool { OPEN, ADD, MERGE, PAGES, SAVE, SPLIT, TEXT, WORD, EXCEL, PNG, IMAGES, LONG,
        COMPRESS, WATERMARK, SIGN }
    private enum ImageTool { EDIT, CROP, ENHANCE, SCREEN, WORD, HANDWRITING, TABLE, HAND_TABLE, QR, RESIZE, PASSPORT, LONG,
        WATERMARK, SIGN, LINE, ID, BOOK, FLATTEN, REPAIR }

    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final ExecutorService previews = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> images = new LruCache<>(22 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
    };
    private final Runnable saveWord = () -> saveWordDraft(true);

    private ScanStore store;
    private ExportHistory exportHistory;
    private String pendingDocumentId;
    private boolean pickingForCapture;
    private static final String INCOMING_FILES = "pendingCapture.files";
    private String importingShortcut;
    private PdfWorkspace pdfStore;
    private ScanUi ui;
    private SharedPreferences preferences;
    private FrameLayout root;
    private Screen screen = Screen.LIBRARY;
    private enum FileFilter { ALL, SCANS, PDF, REVIEWS, EXPORTS }
    private FileFilter fileFilter = FileFilter.ALL;
    private final Set<String> selectedFiles = new LinkedHashSet<>();
    private boolean selectingFiles;
    private static final String DEFAULT_A4 = "export.defaultA4";
    private static final String DEFAULT_PNG = "export.defaultPng";
    private static final String SUBSET = "scanReviewSubset/";
    private static final String SUBSET_SOURCE = "scanReviewSource/";
    private static final String SUBSET_PAGES = "scanReviewSourcePages/";
    private Screen searchReturn = Screen.LIBRARY;
    private Screen reviewReturn = Screen.DETAIL;
    private Screen detailReturn = Screen.LIBRARY;
    private boolean batchEditing;
    private ImageTool batchAction = ImageTool.EDIT;
    private String captureMode = "file";
    private boolean captureHandwriting;
    private boolean toolsResumeCapture;
    private String parkedBatchId;
    private String parkedMode;
    private boolean parkedHandwriting;
    private ImageTool parkedAction = ImageTool.EDIT;
    private Screen parkedReturn = Screen.LIBRARY;
    private Screen parkedEditorReturn = Screen.LIBRARY;
    private String toolQuery = "";
    private boolean filterByFolder;
    private int fileOffset;
    private ScrollView fileScroll;
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
    private AlertDialog bookPreviewDialog;
    private AlertDialog toolPreviewDialog;
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
        exportHistory = new ExportHistory(this);
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
                screen = Screen.DETAIL;
                selectedPage = preferences.getString("page", null);
                cropping = preferences.getBoolean("crop", false);
            }
            if (state == null) {
                screen = Screen.LIBRARY;
                return;
            }
            batchEditing = state.getBoolean("batchEditing");
            batchAction = ImageTool.valueOf(state.getString("batchAction", ImageTool.EDIT.name()));
            captureMode = state.getString("captureMode", "file");
            captureHandwriting = state.getBoolean("captureHandwriting");
            toolsResumeCapture = state.getBoolean("toolsResumeCapture");
            parkedBatchId = state.getString("parkedBatchId");
            parkedMode = state.getString("parkedMode");
            parkedHandwriting = state.getBoolean("parkedHandwriting");
            parkedAction = ImageTool.valueOf(state.getString("parkedAction", ImageTool.EDIT.name()));
            parkedReturn = Screen.valueOf(state.getString("parkedReturn", Screen.LIBRARY.name()));
            parkedEditorReturn = Screen.valueOf(state.getString("parkedEditorReturn", Screen.LIBRARY.name()));
            detailReturn = Screen.valueOf(state.getString("detailReturn", Screen.LIBRARY.name()));
            searchReturn = Screen.valueOf(state.getString("searchReturn", Screen.LIBRARY.name()));
            reviewReturn = Screen.valueOf(state.getString("reviewReturn", Screen.DETAIL.name()));
            fileFilter = FileFilter.valueOf(state.getString("fileFilter", FileFilter.ALL.name()));
            filterByFolder = state.getBoolean("filterByFolder");
            fileOffset = state.getInt("fileOffset");
            selectingFiles = state.getBoolean("selectingFiles");
            ArrayList<String> selection = state.getStringArrayList("selectedFiles");
            if (selection != null) selectedFiles.addAll(selection);
            toolQuery = state.getString("toolQuery", "");
            pendingFile = state.getString("export");
            pendingDocumentId = state.getString("exportDocumentId");
            pickingForCapture = state.getBoolean("pickingForCapture");
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
            if (Screen.TOOLS.name().equals(lastScreen) || Screen.FILES.name().equals(lastScreen)
                    || Screen.LIBRARY.name().equals(lastScreen) || Screen.FOLDER.name().equals(lastScreen)
                    || Screen.SEARCH.name().equals(lastScreen)) {
                screen = Screen.valueOf(lastScreen);
                return;
            }
            if (document == null) return;
            if (Screen.EDITOR.name().equals(lastScreen)) screen = Screen.EDITOR;
            if (reviewReturn == Screen.PDF || detailReturn == Screen.PDF) {
                pdfReturn = Screen.valueOf(state.getString("pdfReturn", Screen.LIBRARY.name()));
                pdfPages = pdfStore.load();
                pdfLoaded = true;
                pdfPage = state.getInt("pdfPage");
            }
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
        state.putBoolean("batchEditing", batchEditing);
        state.putString("batchAction", batchAction.name());
        state.putString("captureMode", captureMode);
        state.putBoolean("captureHandwriting", captureHandwriting);
        state.putBoolean("toolsResumeCapture", toolsResumeCapture);
        state.putString("parkedBatchId", parkedBatchId);
        state.putString("parkedMode", parkedMode);
        state.putBoolean("parkedHandwriting", parkedHandwriting);
        state.putString("parkedAction", parkedAction.name());
        state.putString("parkedReturn", parkedReturn.name());
        state.putString("parkedEditorReturn", parkedEditorReturn.name());
        state.putString("detailReturn", detailReturn.name());
        state.putString("searchReturn", searchReturn.name());
        state.putString("reviewReturn", reviewReturn.name());
        state.putString("fileFilter", fileFilter.name());
        state.putBoolean("filterByFolder", filterByFolder);
        state.putInt("fileOffset", fileScroll == null ? fileOffset : fileScroll.getScrollY());
        state.putBoolean("selectingFiles", selectingFiles);
        state.putStringArrayList("selectedFiles", new ArrayList<>(selectedFiles));
        state.putString("toolQuery", toolQuery);
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
        state.putString("exportDocumentId", pendingDocumentId);
        state.putBoolean("pickingForCapture", pickingForCapture);
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
        if (bookPreviewDialog != null) bookPreviewDialog.dismiss();
        if (toolPreviewDialog != null) toolPreviewDialog.dismiss();
        if (job != null) {
            job.cancel();
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
        switch (screen) {
            case WORD, EXCEL -> {
                if (screen == Screen.WORD && !saveWordDraft(true)) return;
                screen = reviewReturn;
            }
            case EDITOR -> {
                if (!saveCrop(true)) return;
                if (cropping && !batchEditing) {
                    cropping = false;
                    pendingCrop = null;
                } else {
                    if (batchEditing) clearBatch();
                    screen = editorReturn;
                    if (screen == Screen.DETAIL && document == null) screen = detailReturn;
                }
            }
            case DETAIL -> screen = detailReturn;
            case SEARCH -> screen = searchReturn;
            case FILES -> {
                if (selectingFiles) {
                    stopFileSelection();
                    return;
                }
                screen = Screen.LIBRARY;
            }
            case PDF -> screen = pdfReturn;
            case TOOLS -> {
                screen = toolsReturn;
                if (toolsResumeCapture) {
                    resumeParkedCapture();
                    return;
                }
            }
            default -> screen = Screen.LIBRARY;
        }
        if (screen == Screen.SEARCH) searchResults = null;
        remember();
        render();
    }

    private void clearBatch() {
        batchEditing = false;
        batchAction = ImageTool.EDIT;
        cropping = false;
        pendingCrop = null;
    }

    private View withNavigation(View content) {
        LinearLayout layout = ui.vertical();
        layout.addView(content, new LinearLayout.LayoutParams(ui.match(), 0, 1));
        layout.addView(ui.rule());
        LinearLayout bar = ui.horizontal();
        bar.setPadding(ui.dp(14), ui.dp(4), ui.dp(14), ui.dp(3));
        bar.setBackgroundColor(ScanUi.WHITE);
        for (Screen destination : new Screen[]{Screen.LIBRARY, Screen.FILES, Screen.TOOLS}) {
            String label = destination == Screen.LIBRARY ? "首页" : destination == Screen.FILES ? "文件" : "工具";
            int symbol = destination == Screen.LIBRARY ? R.drawable.ic_scan
                    : destination == Screen.FILES ? R.drawable.ic_document : R.drawable.ic_tools;
            boolean active = screen == destination;
            int tint = active ? ScanUi.ACCENT : ScanUi.SECONDARY;

            LinearLayout tab = ui.vertical();
            tab.setGravity(Gravity.CENTER);
            tab.setMinimumHeight(ui.dp(48));
            tab.setPadding(0, ui.dp(2), 0, ui.dp(2));
            tab.setBackground(ui.ripple(0, 10, 0));
            tab.setContentDescription(label);
            tab.setFocusable(true);
            tab.setSelected(active);
            tab.setOnClickListener(v -> {
                if (screen == destination) return;
                if (destination == Screen.TOOLS) toolsReturn = Screen.LIBRARY;
                selectingFiles = false;
                selectedFiles.clear();
                screen = destination;
                render();
            });

            FrameLayout iconSpot = new FrameLayout(this);
            if (active) iconSpot.setBackground(ui.panel(ScanUi.ACCENT_SOFT, 14, 0));
            iconSpot.addView(pageIcon(symbol, tint), new FrameLayout.LayoutParams(ui.dp(19), ui.dp(19), Gravity.CENTER));
            tab.addView(iconSpot, new LinearLayout.LayoutParams(ui.dp(46), ui.dp(23)));
            TextView tabLabel = ui.text(label, 12, tint);
            tabLabel.setGravity(Gravity.CENTER);
            tabLabel.setPadding(0, ui.dp(2), 0, 0);
            tabLabel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            tab.addView(tabLabel);
            bar.addView(tab, new LinearLayout.LayoutParams(0, ui.wrap(), 1));
        }
        layout.addView(bar);
        return layout;
    }

    private ImageView pageIcon(int drawable, int tint) {
        ImageView icon = new ImageView(this);
        icon.setImageResource(drawable);
        icon.setImageTintList(ColorStateList.valueOf(tint));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return icon;
    }

    private void render() {
        back.setEnabled(screen != Screen.LIBRARY);
        contentGeneration++;
        if (fileScroll != null) {
            fileOffset = fileScroll.getScrollY();
            fileScroll = null;
        }
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
            case DETAIL: view = documentDetail(); break;
            case FILES: view = library(); break;
            case WORD: view = wordEditor(); break;
            case EXCEL: view = tableEditor(); break;
            case PDF: view = pdfEditor(); break;
            case TOOLS: view = tools(); break;
            case FOLDER: view = library(); break;
            case SEARCH: view = searchScreen(); break;
            default: view = library();
        }
        if (screen == Screen.LIBRARY || screen == Screen.FILES || screen == Screen.TOOLS) {
            view = withNavigation(view);
        }
        if (job == null && toolsResumeCapture && screen != Screen.TOOLS && screen != Screen.EDITOR
                && screen != Screen.WORD && screen != Screen.EXCEL && screen != Screen.PDF) {
            LinearLayout resume = ui.vertical();
            resume.addView(ui.quietButton("返回拍摄", ScanUi.ACCENT, this::resumeParkedCapture));
            resume.addView(view, new LinearLayout.LayoutParams(ui.match(), 0, 1));
            view = resume;
        }
        if (job == null && preferences.contains(INCOMING_FILES)) {
            LinearLayout recovery = ui.vertical();
            recovery.addView(ui.quietButton("恢复待导入的拍摄批次", ScanUi.ACCENT, this::offerCaptureRecovery));
            recovery.addView(view, new LinearLayout.LayoutParams(ui.match(), 0, 1));
            view = recovery;
        }
        root.removeAllViews();
        root.addView(view, new FrameLayout.LayoutParams(ui.match(), ui.match()));
    }

    private View library() {
        boolean home = screen == Screen.LIBRARY;
        LinearLayout layout = ui.vertical();
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        int maxWidth = getResources().getConfiguration().screenWidthDp >= 600 ? ui.dp(568) : ui.match();
        layout.addView(selectingFiles && !home ? fileSelectionHeader() : libraryHeader(),
                new LinearLayout.LayoutParams(maxWidth, ui.wrap()));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout files = ui.vertical();
        files.setPadding(ui.dp(20), ui.dp(4), ui.dp(20), ui.dp(18));
        scroll.addView(files, new ScrollView.LayoutParams(maxWidth, ui.wrap(), Gravity.CENTER_HORIZONTAL));
        layout.addView(scroll, new LinearLayout.LayoutParams(ui.match(), 0, 1));
        if (home) {
            files.addView(homeActions());
        } else {
            fileScroll = scroll;
            scroll.post(() -> scroll.scrollTo(0, fileOffset));
            if (!selectingFiles) {
                HorizontalScrollView filters = new HorizontalScrollView(this);
                filters.setHorizontalScrollBarEnabled(false);
                LinearLayout options = ui.horizontal();
                String[] labels = {"全部", "扫描件", "PDF", "识别稿", "已导出"};
                for (FileFilter value : FileFilter.values()) {
                    Button filter = ui.quietButton(labels[value.ordinal()],
                            fileFilter == value ? ScanUi.ACCENT : ScanUi.SECONDARY, () -> {
                                fileFilter = value;
                                scroll.scrollTo(0, 0);
                                render();
                            });
                    boolean active = fileFilter == value;
                    filter.setTextSize(13);
                    filter.setPadding(ui.dp(10), 0, ui.dp(10), 0);
                    filter.setBackground(ui.ripple(active ? ScanUi.ACCENT_SOFT : 0, 14, 0));
                    filter.setSelected(active);
                    LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(ui.wrap(), ui.wrap());
                    size.setMarginEnd(ui.dp(3));
                    options.addView(filter, size);
                }
                filters.addView(options);
                files.addView(filters);
                if (fileFilter == FileFilter.PDF || fileFilter == FileFilter.ALL && !filterByFolder) addPdfLibrary(files);
                if (fileFilter == FileFilter.EXPORTS) {
                    addExportHistory(files);
                    return layout;
                }
                if (fileFilter == FileFilter.PDF) return layout;
                Button category = ui.quietButton("分类 · " + (filterByFolder ? folder.label : "全部"),
                        ScanUi.SECONDARY, () -> chooseFileFolder(scroll));
                category.setTextSize(13);
                category.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                category.setPadding(0, 0, ui.dp(12), 0);
                files.addView(category, new LinearLayout.LayoutParams(ui.wrap(), ui.wrap()));
            }
        }
        List<String> unreadable = new ArrayList<>();
        List<ScanDocument> scans;
        try {
            scans = store.list(unreadable);
        } catch (IOException failure) {
            scans = new ArrayList<>();
            files.addView(ui.text("无法读取本机扫描件", 14, ScanUi.SECONDARY));
        }
        if (!unreadable.isEmpty()) files.addView(ui.text(unreadable.size() + " 份扫描件暂时无法读取",
                14, ScanUi.SECONDARY));
        if (!home && filterByFolder) scans.removeIf(item -> item.folder != folder);
        if (!home && fileFilter == FileFilter.REVIEWS) scans.removeIf(item -> !hasReview(item));
        if (home) {
            int drafts = 0;
            for (ScanDocument item : scans) {
                if (drafts == 2) break;
                if (!hasReview(item)) continue;
                if (drafts++ == 0) {
                    TextView heading = pageSectionHeading("继续处理");
                    heading.setPadding(0, ui.dp(17), 0, ui.dp(2));
                    files.addView(heading);
                }
                files.addView(libraryRow(item));
            }
            LinearLayout section = ui.horizontal();
            section.setPadding(0, ui.dp(drafts > 0 ? 5 : 12), 0, 0);
            section.addView(pageSectionHeading("最近打开"), new LinearLayout.LayoutParams(0, ui.wrap(), 1));
            Button all = ui.quietButton("查看全部", ScanUi.ACCENT, () -> {
                screen = Screen.FILES;
                render();
            });
            all.setTextSize(13);
            all.setPadding(ui.dp(9), 0, 0, 0);
            section.addView(all);
            files.addView(section);
        }
        if (scans.isEmpty()) {
            files.addView(ui.text(home ? "拍张纸，或导入已有图片" : "没有符合条件的扫描件", 14, ScanUi.SECONDARY));
        }
        for (int i = 0; i < (home ? Math.min(3, scans.size()) : scans.size()); i++) {
            files.addView(libraryRow(scans.get(i)));
        }
        return layout;
    }

    private void addPdfLibrary(LinearLayout files) {
        List<PdfWorkspace.Page> available = pdfPages;
        if (!pdfLoaded) {
            try {
                available = pdfStore.load();
            } catch (IOException unreadable) {
                files.addView(ui.text("暂时无法读取 PDF", 14, ScanUi.SECONDARY));
            }
        }
        java.util.Map<String, Integer> pageCounts = new java.util.HashMap<>();
        for (PdfWorkspace.Page page : available) pageCounts.merge(page.source, 1, Integer::sum);
        Set<String> sources = new LinkedHashSet<>();
        if (!pdfLoaded && !available.isEmpty()) {
            pdfPages = available;
            pdfLoaded = true;
        }
        for (PdfWorkspace.Page page : available) {
            if (!sources.add(page.source)) continue;
            files.addView(pdfLibraryRow(page.name, pageCounts.get(page.source), () -> {
                openPdf(PdfTool.OPEN);
                if (!pdfLoaded) return;
                for (int i = 0; i < pdfPages.size(); i++) {
                    if (pdfPages.get(i).source.equals(page.source)) {
                        pdfPage = i;
                        rememberPdfPosition();
                        render();
                        break;
                    }
                }
            }));
        }
        if (fileFilter == FileFilter.PDF && available.isEmpty()) {
            files.addView(ui.button("导入 PDF", false, () -> openPdf(PdfTool.ADD)));
        }
    }

    private View pdfLibraryRow(String name, int pages, Runnable open) {
        LinearLayout row = ui.horizontal();
        row.setMinimumHeight(ui.dp(64));
        row.setPadding(0, ui.dp(5), 0, ui.dp(5));
        row.setBackground(ui.ripple(0, 8, 0));
        row.setContentDescription(name);
        if (Build.VERSION.SDK_INT >= 30) row.setStateDescription("PDF，" + pages + " 页");
        row.setFocusable(true);
        row.setOnClickListener(view -> open.run());

        FrameLayout iconSpot = new FrameLayout(this);
        iconSpot.setBackground(ui.panel(ScanUi.SURFACE, 7, ScanUi.LINE));
        iconSpot.addView(pageIcon(R.drawable.ic_pdf, ScanUi.SECONDARY),
                new FrameLayout.LayoutParams(ui.dp(20), ui.dp(20), Gravity.CENTER));
        row.addView(iconSpot, new LinearLayout.LayoutParams(ui.dp(40), ui.dp(50)));

        LinearLayout labels = ui.vertical();
        labels.setPadding(ui.dp(11), ui.dp(3), ui.dp(6), ui.dp(3));
        labels.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        TextView title = ui.heading(name, 15);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(title);
        TextView metadata = ui.text("PDF · " + pages + " 页", 12, ScanUi.SECONDARY);
        metadata.setPadding(0, ui.dp(4), 0, 0);
        labels.addView(metadata);
        row.addView(labels, new LinearLayout.LayoutParams(0, ui.wrap(), 1));

        FrameLayout indicator = new FrameLayout(this);
        indicator.addView(pageIcon(R.drawable.ic_next, ScanUi.SECONDARY),
                new FrameLayout.LayoutParams(ui.dp(14), ui.dp(14), Gravity.CENTER));
        row.addView(indicator, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        return row;
    }

    private void addExportHistory(LinearLayout files) {
        addExportHistory(files, null);
    }

    private void addExportHistory(LinearLayout files, String documentId) {
        try {
            List<ExportHistory.Entry> entries = exportHistory.list();
            if (documentId != null) entries.removeIf(entry -> !documentId.equals(entry.documentId));
            if (entries.isEmpty()) files.addView(ui.text("暂无导出记录", 14, ScanUi.SECONDARY));
            int generation = contentGeneration;
            for (ExportHistory.Entry entry : entries) {
                Button record = ui.quietButton(entry.name, ScanUi.INK, () -> exportRecord(entry));
                record.setTag(entry);
                files.addView(record);
                TextView location = ui.text(savedLocation(entry.uri) + " · " + formattedBytes(entry.bytes)
                        + " · 检查可访问性…", 12, ScanUi.SECONDARY);
                files.addView(location);
                files.addView(ui.rule());
                previews.execute(() -> {
                    boolean available = canReadOutput(entry.uri);
                    main.post(() -> {
                        if (generation == contentGeneration) {
                            location.setText(savedLocation(entry.uri) + " · " + formattedBytes(entry.bytes)
                                    + (available ? "" : " · 文件不可访问"));
                        }
                    });
                });
            }
        } catch (IOException unreadable) {
            files.addView(ui.text("暂时无法读取导出记录", 14, ScanUi.SECONDARY));
        }
    }

    private String savedLocation(Uri uri) {
        if (android.provider.DocumentsContract.isDocumentUri(this, uri)) {
            try {
                String id = android.provider.DocumentsContract.getDocumentId(uri);
                if ("com.android.externalstorage.documents".equals(uri.getAuthority()) && id.startsWith("primary:")) {
                    return "本机存储/" + id.substring("primary:".length());
                }
                if ("com.android.providers.downloads.documents".equals(uri.getAuthority())) return "下载";
            } catch (IllegalArgumentException invalidUri) {
                // Other document providers may expose opaque IDs.
            }
        }
        try {
            return getPackageManager().resolveContentProvider(uri.getAuthority(), 0)
                    .loadLabel(getPackageManager()).toString();
        } catch (RuntimeException unavailable) {
            return "所选文件位置";
        }
    }

    private String formattedBytes(long bytes) {
        return android.text.format.Formatter.formatShortFileSize(this, bytes);
    }

    private boolean canReadOutput(Uri uri) {
        try (android.os.ParcelFileDescriptor handle = getContentResolver().openFileDescriptor(uri, "r")) {
            return handle != null;
        } catch (IOException | SecurityException unavailable) {
            return false;
        }
    }

    private void exportRecord(ExportHistory.Entry entry) {
        FileFormat format;
        try {
            format = FileFormat.valueOf(entry.format);
        } catch (IllegalArgumentException unsupported) {
            error("此导出格式暂不支持重新打开", null);
            return;
        }
        task("查看导出记录", false, state -> canReadOutput(entry.uri), available -> {
            ScanSheet sheet = new ScanSheet(this, ui, entry.name);
            sheet.detail(available ? savedLocation(entry.uri) + " · " + formattedBytes(entry.bytes)
                    : "文件不可访问，可能已移动、删除或权限已失效。");
            if (available) {
                sheet.option(R.drawable.ic_document, "打开文件", null, false, () -> openOutput(entry.uri, format));
                sheet.option(R.drawable.ic_share, "分享文件", null, false, () -> shareOutput(entry.uri, format));
            }
            if (entry.documentId != null) {
                sheet.option(R.drawable.ic_scan, "查看关联原稿", null, false, () -> {
                    try {
                        ScanDocument source = store.load(entry.documentId);
                        if (source == null) error("关联原稿已移除", null);
                        else open(source);
                    } catch (IOException unreadable) {
                        error("无法打开关联原稿", null);
                    }
                });
            }
            sheet.option(R.drawable.ic_delete, "移除导出记录", "保留外部文件", false, () -> {
                try {
                    exportHistory.remove(entry.id);
                    render();
                } catch (IOException failure) {
                    error("无法移除记录", null);
                }
            });
            sheet.show();
        }, null);
    }

    private void shareOutput(Uri uri, FileFormat format) {
        Intent send = new Intent(Intent.ACTION_SEND).setType(format.mime).putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newRawUri(null, uri));
        try {
            startActivity(Intent.createChooser(send, "分享文件"));
        } catch (ActivityNotFoundException unavailable) {
            error("没有可分享此文件的应用", null);
        }
    }

    private void chooseFileFolder(ScrollView scroll) {
        ScanSheet sheet = new ScanSheet(this, ui, "文件分类");
        sheet.option(R.drawable.ic_document, "全部", null, !filterByFolder, () -> {
            filterByFolder = false;
            scroll.scrollTo(0, 0);
            render();
        });
        for (ScanDocument.Folder value : ScanDocument.Folder.values()) {
            sheet.option(R.drawable.ic_document, value.label, null, filterByFolder && folder == value, () -> {
                folder = value;
                filterByFolder = true;
                scroll.scrollTo(0, 0);
                render();
            });
        }
        sheet.show();
    }

    private View fileSelectionHeader() {
        LinearLayout header = ui.vertical();
        header.addView(ui.header("已选 " + selectedFiles.size() + " 份", this::stopFileSelection,
                "取消", this::stopFileSelection));
        LinearLayout actions = ui.horizontal();
        Button move = ui.button("移动分类", false, this::chooseBulkFolder);
        Button remove = ui.button("删除", false, this::confirmBulkDelete);
        move.setEnabled(!selectedFiles.isEmpty());
        remove.setEnabled(!selectedFiles.isEmpty());
        actions.addView(move, new LinearLayout.LayoutParams(0, ui.wrap(), 1));
        actions.addView(remove, new LinearLayout.LayoutParams(0, ui.wrap(), 1));
        header.addView(actions);
        return header;
    }

    private void toggleFileSelection(ScanDocument item) {
        selectingFiles = true;
        if (!selectedFiles.add(item.id)) selectedFiles.remove(item.id);
        render();
    }

    private void stopFileSelection() {
        selectingFiles = false;
        selectedFiles.clear();
        render();
    }

    private void chooseBulkFolder() {
        ScanSheet sheet = new ScanSheet(this, ui, "移动 " + selectedFiles.size() + " 份扫描件");
        for (ScanDocument.Folder target : ScanDocument.Folder.values()) {
            sheet.option(R.drawable.ic_document, target.label, null, false, () -> updateSelectedFiles(target));
        }
        sheet.show();
    }

    private void confirmBulkDelete() {
        new AlertDialog.Builder(this).setTitle("删除所选的 " + selectedFiles.size() + " 份扫描件？")
                .setMessage("将移除应用内的原图和校对稿。已导出的文件仍保留。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> updateSelectedFiles(null)).show();
    }

    private void updateSelectedFiles(ScanDocument.Folder folder) {
        List<String> ids = new ArrayList<>(selectedFiles);
        task(folder == null ? "删除扫描件" : "移动扫描件", false, state -> {
            Set<String> failed = new LinkedHashSet<>();
            for (String id : ids) {
                try {
                    ScanDocument item = store.load(id);
                    if (item == null) continue;
                    if (folder == null) store.delete(item);
                    else store.save(item.filed(folder));
                } catch (IOException failure) {
                    failed.add(id);
                }
            }
            return failed;
        }, failed -> {
            selectedFiles.retainAll(failed);
            selectingFiles = !failed.isEmpty();
            if (document != null && ids.contains(document.id) && !failed.contains(document.id)) {
                document = folder == null ? null : document.filed(folder);
                remember();
            }
            render();
            if (!failed.isEmpty()) error(failed.size() + " 份扫描件未能处理，可重试所选扫描件", null);
        }, null);
    }

    private View libraryHeader() {
        LinearLayout bar = ui.horizontal();
        bar.setPadding(ui.dp(21), ui.dp(8), ui.dp(16), ui.dp(5));
        TextView title = ui.display(screen == Screen.LIBRARY ? getString(R.string.app_name) : "文件", 27);
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
            searchReturn = screen;
            screen = Screen.SEARCH;
            render();
        }), new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        if (screen == Screen.FILES) {
            bar.addView(ui.icon(R.drawable.ic_add, "新建或导入", this::newMaterial),
                    new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
            Button settings = ui.quietButton("设置", ScanUi.INK, this::settings);
            settings.setTextSize(13);
            settings.setPadding(ui.dp(8), 0, ui.dp(8), 0);
            bar.addView(settings);
        }
        return bar;
    }

    private void newMaterial() {
        ScanSheet sheet = new ScanSheet(this, ui, "新建或导入");
        sheet.option(R.drawable.ic_camera, "拍照扫描", null, false, this::startScanCamera);
        sheet.option(R.drawable.ic_gallery, "导入照片", null, false, this::pick);
        sheet.option(R.drawable.ic_pdf, "导入 PDF", null, false, () -> openPdf(PdfTool.ADD));
        sheet.show();
    }

    private void settings() {
        String version;
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException unavailable) {
            version = "";
        }
        ScanSheet sheet = new ScanSheet(this, ui, "设置");
        sheet.detail("页光相机 " + version);
        sheet.option(R.drawable.ic_document, "本机存储", "原稿和校对稿保存在应用内", false,
                () -> textResult("本机存储", "原稿和校对稿保存在本机应用内。导出的位置由系统文件选择器指定。"));
        sheet.option(R.drawable.ic_camera, "权限与隐私", "相机、选图和保存", false,
                () -> textResult("权限与隐私", "相机仅在拍摄时使用。也可以通过系统选择器导入照片或 PDF。"
                        + "扫描与识别均在本机处理。"));
        sheet.option(R.drawable.ic_pdf, "默认 PDF 页面尺寸", defaultA4() ? "A4 纸张" : "图片比例",
                false, () -> chooseExportDefault(true));
        sheet.option(R.drawable.ic_image, "默认图片格式", defaultPng() ? "PNG" : "JPG",
                false, () -> chooseExportDefault(false));
        sheet.option(R.drawable.ic_document, "开源许可证", null, false, this::openLicenses);
        sheet.show();
    }

    private boolean defaultA4() {
        return preferences.getBoolean(DEFAULT_A4, true);
    }

    private boolean defaultPng() {
        return preferences.getBoolean(DEFAULT_PNG, false);
    }

    private void chooseExportDefault(boolean pdf) {
        boolean first = pdf ? defaultA4() : !defaultPng();
        String[] options = pdf ? new String[]{"A4 纸张", "图片比例"} : new String[]{"JPG", "PNG"};
        new AlertDialog.Builder(this).setTitle(pdf ? "默认 PDF 页面尺寸" : "默认图片格式")
                .setSingleChoiceItems(options, first ? 0 : 1, (view, which) -> {})
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (view, which) -> {
                    int checked = ((AlertDialog) view).getListView().getCheckedItemPosition();
                    preferences.edit().putBoolean(pdf ? DEFAULT_A4 : DEFAULT_PNG,
                            pdf ? checked == 0 : checked == 1).apply();
                    settings();
                }).show();
    }

    private void openLicenses() {
        try {
            String[] licenses = getAssets().list("licenses");
            if (licenses == null || licenses.length == 0) return;
            new AlertDialog.Builder(this).setTitle("开源许可证").setItems(licenses, (dialog, index) -> {
                String name = licenses[index];
                task("打开许可证", false, state -> {
                    try (java.io.InputStream input = getAssets().open("licenses/" + name);
                            java.io.ByteArrayOutputStream text = new java.io.ByteArrayOutputStream()) {
                        byte[] buffer = new byte[8192];
                        for (int size; (size = input.read(buffer)) != -1; ) text.write(buffer, 0, size);
                        return text.toString("UTF-8");
                    }
                }, text -> textResult(name, name.endsWith(".html") ? android.text.Html.fromHtml(text,
                        android.text.Html.FROM_HTML_MODE_LEGACY).toString() : text), null);
            }).setNegativeButton("关闭", null).show();
        } catch (IOException unavailable) {
            error("暂时无法读取许可证", null);
        }
    }

    private View homeActions() {
        LinearLayout actions = ui.vertical();
        LinearLayout scan = ui.horizontal();
        scan.setMinimumHeight(ui.dp(101));
        scan.setPadding(ui.dp(17), ui.dp(15), ui.dp(14), ui.dp(15));
        scan.setBackground(ui.ripple(ScanUi.INK, 15, 0));
        scan.setContentDescription("拍照扫描");
        scan.setFocusable(true);
        scan.setOnClickListener(v -> startScanCamera());

        LinearLayout labels = ui.vertical();
        labels.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        TextView title = ui.heading("拍照扫描", 21);
        title.setTextColor(ScanUi.WHITE);
        labels.addView(title);
        TextView detail = ui.text("连续拍摄，逐页校正", 13, 0xffddd4cc);
        detail.setPadding(0, ui.dp(7), 0, 0);
        labels.addView(detail);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, ui.wrap(), 1);
        labelParams.setMarginEnd(ui.dp(10));
        scan.addView(labels, labelParams);

        FrameLayout iconSpot = new FrameLayout(this);
        iconSpot.setBackground(ui.panel(0xff453e36, 14, 0));
        iconSpot.addView(pageIcon(R.drawable.ic_camera, ScanUi.PAPER),
                new FrameLayout.LayoutParams(ui.dp(24), ui.dp(24), Gravity.CENTER));
        scan.addView(iconSpot, new LinearLayout.LayoutParams(ui.dp(47), ui.dp(47)));
        actions.addView(scan, new LinearLayout.LayoutParams(ui.match(), ui.wrap()));

        View importPhotos = actionTile(R.drawable.ic_gallery, "导入照片", "从相册导入", false, this::pick);
        View pdf = actionTile(R.drawable.ic_pdf, "打开 PDF", "打开 PDF", false, () -> openPdf(PdfTool.OPEN));
        addActionRow(actions, importPhotos, pdf, 9);
        View words = actionTile(R.drawable.ic_text, "提取文字", "提取文字", true, () -> startCamera(ImageTool.WORD));
        View tables = actionTile(R.drawable.ic_table, "提取表格", "提取表格", true, () -> startCamera(ImageTool.TABLE));
        addActionRow(actions, words, tables, 7);
        return actions;
    }

    private void addActionRow(LinearLayout parent, View first, View second, int topGap) {
        LinearLayout row = ui.horizontal();
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, ui.wrap(), 1);
        left.setMarginEnd(ui.dp(8));
        row.addView(first, left);
        row.addView(second, new LinearLayout.LayoutParams(0, ui.wrap(), 1));
        LinearLayout.LayoutParams rowSize = new LinearLayout.LayoutParams(ui.match(), ui.wrap());
        rowSize.topMargin = ui.dp(topGap);
        parent.addView(row, rowSize);
    }

    private View actionTile(int drawable, String title, String description, boolean emphasized, Runnable action) {
        LinearLayout item = ui.horizontal();
        item.setMinimumHeight(ui.dp(51));
        item.setPadding(ui.dp(11), ui.dp(8), ui.dp(8), ui.dp(8));
        item.setBackground(ui.ripple(emphasized ? ScanUi.ACCENT_SOFT : ScanUi.SURFACE, 10, 0));
        item.setContentDescription(description);
        item.setFocusable(true);
        item.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams iconSize = new LinearLayout.LayoutParams(ui.dp(18), ui.dp(18));
        iconSize.setMarginEnd(ui.dp(8));
        item.addView(pageIcon(drawable, emphasized ? ScanUi.ACCENT : ScanUi.SECONDARY), iconSize);
        TextView label = ui.text(title, 14, ScanUi.INK);
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        item.addView(label, new LinearLayout.LayoutParams(0, ui.wrap(), 1));
        return item;
    }

    private TextView pageSectionHeading(String label) {
        TextView heading = ui.heading(label, 16);
        heading.setAccessibilityHeading(true);
        return heading;
    }

    private View libraryRow(ScanDocument item) {
        LinearLayout row = ui.horizontal();
        row.setPadding(0, ui.dp(5), 0, ui.dp(5));
        ImageView thumb = new ImageView(this);
        thumb.setImageResource(R.drawable.ic_document);
        thumb.setScaleType(ImageView.ScaleType.FIT_CENTER);
        thumb.setBackground(ui.panel(ScanUi.SURFACE, 7, ScanUi.LINE));
        thumb.setClipToOutline(true);
        thumb.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(thumb, new LinearLayout.LayoutParams(ui.dp(40), ui.dp(50)));
        if (!item.pages.isEmpty()) {
            loadImage(item, item.pages.get(0), false, true, bitmap -> {
                if (bitmap != null) thumb.setImageBitmap(bitmap);
            });
        }
        LinearLayout label = ui.vertical();
        label.setPadding(ui.dp(11), ui.dp(3), ui.dp(6), ui.dp(3));
        label.setMinimumHeight(ui.dp(48));
        label.setGravity(Gravity.CENTER_VERTICAL);
        label.setFocusable(true);
        String displayName = DEFAULT_SCAN_NAME.matcher(item.name).replaceFirst("扫描件 $1/$2 $3:$4");
        TextView name = ui.heading(displayName, 15);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        label.addView(name);
        String date = new SimpleDateFormat("M/d HH:mm", Locale.CHINA).format(new Date(item.modifiedAt));
        String detail = item.pages.size() + " 页";
        detail += "  ·  " + date;
        if (item.folder != ScanDocument.Folder.NONE) detail += "  ·  " + item.folder.label;
        if (hasReview(item)) detail += "  ·  有识别稿";
        TextView metadata = ui.text(detail, 12, ScanUi.SECONDARY);
        metadata.setPadding(0, ui.dp(4), 0, 0);
        label.addView(metadata);
        label.setContentDescription(item.name + "，" + item.pages.size() + " 页");
        boolean filePage = screen == Screen.FILES;
        View.OnClickListener openOrSelect = v -> {
            if (filePage && selectingFiles) toggleFileSelection(item);
            else open(item);
        };
        label.setOnClickListener(openOrSelect);
        thumb.setOnClickListener(openOrSelect);
        View.OnLongClickListener select = v -> {
            if (filePage) toggleFileSelection(item);
            else documentMenu(item);
            return true;
        };
        label.setOnLongClickListener(select);
        thumb.setOnLongClickListener(select);
        row.addView(label, new LinearLayout.LayoutParams(0, ui.wrap(), 1));
        if (filePage && selectingFiles) {
            CheckBox picked = new CheckBox(this);
            picked.setContentDescription("选择 " + item.name);
            picked.setChecked(selectedFiles.contains(item.id));
            picked.setOnClickListener(v -> toggleFileSelection(item));
            row.addView(picked, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        } else {
            ImageView more = ui.icon(R.drawable.ic_more, item.name + "的更多操作", () -> documentMenu(item));
            more.setPadding(ui.dp(15), ui.dp(15), ui.dp(15), ui.dp(15));
            more.setImageTintList(ColorStateList.valueOf(ScanUi.SECONDARY));
            row.addView(more, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        }
        return row;
    }

    private void documentMenu(ScanDocument item) {
        ScanSheet sheet = new ScanSheet(this, ui, item.name);
        sheet.option(R.drawable.ic_edit, "重命名", null, false, () -> rename(item));
        sheet.option(R.drawable.ic_document, "移动到分类", item.folder.label, false, () -> fileDocument(item));
        sheet.option(R.drawable.ic_document, "查看关联结果", null, false, () -> linkedExports(item));
        sheet.option(R.drawable.ic_delete, "删除扫描件", null, false, () -> new AlertDialog.Builder(this)
                .setTitle("删除扫描件？").setMessage("将删除本应用里的原图及编辑记录。已导出的文件仍可使用。")
                .setNegativeButton("取消", null).setPositiveButton("删除", (dialog, which) -> {
                    try {
                        store.delete(item);
                        if (document != null && document.id.equals(item.id)) {
                            document = null;
                            if (screen == Screen.DETAIL) screen = detailReturn;
                            remember();
                        }
                        render();
                    } catch (IOException failure) {
                        error("无法删除扫描件", null);
                    }
                }).show());
        sheet.show();
    }

    private void linkedExports(ScanDocument item) {
        ScrollView scroll = new ScrollView(this);
        LinearLayout records = ui.vertical();
        records.setPadding(ui.dp(18), 0, ui.dp(18), ui.dp(10));
        addExportHistory(records, item.id);
        scroll.addView(records);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("关联导出结果")
                .setView(scroll).setPositiveButton("关闭", null).create();
        for (int i = 0; i < records.getChildCount(); i++) {
            View child = records.getChildAt(i);
            if (child instanceof Button button) {
                View.OnClickListener openRecord = v -> {
                    dialog.dismiss();
                    if (button.getTag() instanceof ExportHistory.Entry entry) exportRecord(entry);
                };
                button.setOnClickListener(openRecord);
            }
        }
        dialog.show();
    }

    private void fileDocument(ScanDocument item) {
        ScanSheet folders = new ScanSheet(this, ui, "移动到分类");
        for (ScanDocument.Folder destination : ScanDocument.Folder.values()) {
            folders.option(R.drawable.ic_document, destination.label, null, item.folder == destination, () -> {
                try {
                    ScanDocument filed = item.filed(destination);
                    store.save(filed);
                    if (document != null && document.id.equals(item.id)) document = filed;
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
        toolsReturn = screen;
        screen = Screen.TOOLS;
        render();
    }

    private View tools() {
        ToolShelf shelf = new ToolShelf(this, ui, toolsResumeCapture ? this::navigateBack : null);
        shelf.search(toolQuery, value -> toolQuery = value);
        shelf.section("识别", "文字与表格",
                new ToolShelf.Item("提取文字", R.drawable.ic_text, "校对正文并导出 Word", "手写 图片转文字",
                        () -> imageToolPicker("提取文字", ImageTool.WORD)),
                new ToolShelf.Item("提取表格", R.drawable.ic_table, "校对单元格并导出 Excel", "图片转表格 手写",
                        () -> imageToolPicker("提取表格", ImageTool.TABLE)));
        shelf.section("图片", "图片处理",
                imageTile("图像矫正", R.drawable.ic_crop, ImageTool.CROP),
                imageTile("文档增强", R.drawable.ic_enhance, ImageTool.ENHANCE),
                imageTile("证件复印件", R.drawable.ic_id, ImageTool.ID),
                imageTile("证件照裁尺寸", R.drawable.ic_camera, ImageTool.PASSPORT),
                new ToolShelf.Item("书页处理", R.drawable.ic_document, "拆分对开页或展平曲面", "书本 书页拆分 书页展平",
                        () -> bookTools(false)),
                imageTile("拍屏去彩纹", R.drawable.ic_camera, ImageTool.SCREEN),
                imageTile("等比改尺寸", R.drawable.ic_image, ImageTool.RESIZE),
                imageTile("合并长图", R.drawable.ic_gallery, ImageTool.LONG),
                new ToolShelf.Item("添加水印", R.drawable.ic_edit, "为图片或 PDF 添加水印", "",
                        () -> chooseMarkSource(false)),
                new ToolShelf.Item("手写签名", R.drawable.ic_edit, "在图片或 PDF 上放置手写签名", "",
                        () -> chooseMarkSource(true)),
                imageTile("手动消除", R.drawable.ic_enhance, ImageTool.REPAIR),
                imageTile("提取线稿", R.drawable.ic_edit, ImageTool.LINE),
                imageTile("读二维码", R.drawable.ic_qr, ImageTool.QR));
        shelf.section("PDF", "PDF 处理",
                pdfTile("PDF 阅读", R.drawable.ic_pdf, PdfTool.OPEN),
                new ToolShelf.Item("合并 PDF", R.drawable.ic_add, "组合多份 PDF 的选中页面", "拼PDF 拼 PDF PDF 合并",
                        () -> openPdf(PdfTool.MERGE)),
                pdfTile("拆分 PDF", R.drawable.ic_crop, PdfTool.PAGES),
                pdfTile("PDF 转 Word", R.drawable.ic_document, PdfTool.WORD),
                pdfTile("PDF 转 Excel", R.drawable.ic_table, PdfTool.EXCEL),
                pdfTile("PDF 转图片", R.drawable.ic_image, PdfTool.IMAGES),
                pdfTile("PDF 转长图", R.drawable.ic_gallery, PdfTool.LONG),
                pdfTile("PDF 压缩", R.drawable.ic_down, PdfTool.COMPRESS));
        activeShelf = shelf;
        return shelf.view(toolsScrollY);
    }

    private void chooseMarkSource(boolean signature) {
        String title = signature ? "手写签名" : "添加水印";
        ScanSheet sheet = new ScanSheet(this, ui, title);
        sheet.option(R.drawable.ic_image, "图片或扫描件", null, false,
                () -> imageToolPicker(title, signature ? ImageTool.SIGN : ImageTool.WATERMARK));
        sheet.option(R.drawable.ic_pdf, "PDF", null, false,
                () -> openPdf(signature ? PdfTool.SIGN : PdfTool.WATERMARK));
        sheet.show();
    }

    private void bookTools(boolean current) {
        ScanSheet sheet = new ScanSheet(this, ui, "书页处理");
        sheet.option(R.drawable.ic_crop, "拆分左右页", "把对开页另存为两页 PDF", false, () -> {
            if (current) runImageTool(ImageTool.BOOK);
            else imageToolPicker("书页拆分", ImageTool.BOOK);
        });
        sheet.option(R.drawable.ic_document, "展平书页", "预览曲面修正，也可恢复原页", false, () -> {
            if (current) runImageTool(ImageTool.FLATTEN);
            else imageToolPicker("书页展平", ImageTool.FLATTEN);
        });
        sheet.show();
    }

    private ToolShelf.Item pdfTile(String label, int icon, PdfTool action) {
        String detail = switch (action) {
            case OPEN -> "导入并翻看 PDF";
            case PAGES -> "把选中页分别保存为 PDF";
            case WORD -> "校对正文后导出 Word";
            case EXCEL -> "校对表格后导出 Excel";
            case IMAGES -> "保存当前页或批量转为图片";
            case LONG -> "把选中页拼成一张长图";
            case COMPRESS -> "减小含有大图的 PDF";
            default -> "";
        };
        return new ToolShelf.Item(label, icon, detail, "", () -> openPdf(action));
    }

    private ToolShelf.Item imageTile(String label, int icon, ImageTool action) {
        String detail = switch (action) {
            case CROP -> "调整纸边与透视";
            case ENHANCE -> "减淡阴影并增强清晰度";
            case ID -> "把证件正反面排到一张 A4";
            case PASSPORT -> "按一寸或二寸裁切照片";
            case SCREEN -> "减淡拍摄屏幕产生的彩纹";
            case RESIZE -> "按指定宽高调整图片";
            case LONG -> "按页序纵向拼接图片";
            case REPAIR -> "另存手动消除后的图片";
            case LINE -> "另存黑白线条图";
            case QR -> "读取并复制二维码内容";
            default -> "";
        };
        return new ToolShelf.Item(label, icon, detail, action == ImageTool.ENHANCE ? "去阴影" : "",
                () -> imageToolPicker(label, action));
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
                ScanDocument renamed = item.renamed(name);
                store.save(renamed);
                if (document != null && document.id.equals(item.id)) document = renamed;
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
        detailReturn = screen == Screen.DETAIL ? detailReturn : screen;
        document = item;
        selectedPage = pageId != null ? pageId : item.pages.isEmpty() ? null : item.pages.get(0).id;
        clearBatch();
        reviewReturn = Screen.DETAIL;
        screen = Screen.DETAIL;
        remember();
        render();
    }

    private void editFromDetail(boolean crop) {
        editorReturn = Screen.DETAIL;
        cropping = crop;
        pendingCrop = null;
        screen = Screen.EDITOR;
        remember();
        render();
    }

    private static final String[] REVIEW_KEYS = {ScanRecognition.WORD, ScanRecognition.HANDWRITING,
            ScanRecognition.EXCEL, ScanRecognition.HAND_TABLE};

    private boolean hasReview(ScanDocument scan) {
        for (String key : REVIEW_KEYS) {
            try {
                if (store.loadReview(scan, key) != null || store.reviewIsOutdated(scan, key)) return true;
            } catch (IOException | org.json.JSONException unreadable) {
                // A damaged draft does not hide its source document.
            }
        }
        return false;
    }

    private View documentDetail() {
        if (document == null) return library();
        LinearLayout layout = ui.vertical();
        LinearLayout top = ui.horizontal();
        top.setPadding(ui.dp(7), ui.dp(5), ui.dp(16), ui.dp(5));
        top.addView(ui.icon(R.drawable.ic_back, "返回", this::navigateBack),
                new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        TextView title = ui.heading(document.name, 21);
        title.setAccessibilityHeading(true);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleWidth = new LinearLayout.LayoutParams(0, ui.wrap(), 1f);
        titleWidth.setMarginEnd(ui.dp(8));
        top.addView(title, titleWidth);
        Button export = ui.button("导出", true, this::showExportFormats);
        export.setTextSize(14);
        top.addView(export);
        layout.addView(top);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = ui.vertical();
        body.setPadding(ui.dp(20), ui.dp(4), ui.dp(20), ui.dp(20));
        int maxWidth = getResources().getConfiguration().screenWidthDp >= 600 ? ui.dp(568) : ui.match();
        scroll.addView(body, new ScrollView.LayoutParams(maxWidth, ui.wrap(), Gravity.CENTER_HORIZONTAL));
        layout.addView(scroll, new LinearLayout.LayoutParams(ui.match(), 0, 1));
        String pdfSource = preferences.getString("pdfReviewSource/" + document.id, null);
        String scanSource = preferences.getString(SUBSET_SOURCE + document.id, null);
        if (scanSource != null) {
            body.addView(ui.quietButton("打开原扫描件", ScanUi.ACCENT, () -> openSubsetSource(scanSource)));
        }
        LinearLayout meta = ui.horizontal();
        String source = pdfSource == null ? "" : " · 原 PDF " + pdfSource;
        TextView pageCount = ui.text(document.pages.size() + " 页" + source, 13, ScanUi.SECONDARY);
        meta.addView(pageCount, new LinearLayout.LayoutParams(0, ui.wrap(), 1f));
        meta.addView(ui.icon(R.drawable.ic_more, "更多操作", () -> documentMenu(document)),
                new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        body.addView(meta);
        addActionRow(body, actionTile(R.drawable.ic_edit, "编辑图片", "编辑图片", false, () -> editFromDetail(false)),
                actionTile(R.drawable.ic_document, "管理页面", "管理页面", false, this::manageScanPages), 0);
        addActionRow(body, actionTile(R.drawable.ic_text, "提取文字", "提取文字", true, () -> chooseRecognitionMode(false)),
                actionTile(R.drawable.ic_table, "提取表格", "提取表格", true, () -> chooseRecognitionMode(true)), 7);
        TextView pagesHeading = pageSectionHeading("扫描页");
        pagesHeading.setPadding(0, ui.dp(20), 0, ui.dp(7));
        body.addView(pagesHeading);
        HorizontalScrollView strip = new HorizontalScrollView(this);
        strip.setHorizontalScrollBarEnabled(false);
        LinearLayout thumbnails = ui.horizontal();
        for (int i = 0; i < document.pages.size(); i++) {
            ScanDocument.Page page = document.pages.get(i);
            int number = i + 1;
            LinearLayout item = ui.vertical();
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setPadding(0, 0, 0, ui.dp(8));
            item.setBackground(ui.ripple(0, 9, 0));
            ImageView thumb = new ImageView(this);
            thumb.setScaleType(ImageView.ScaleType.FIT_CENTER);
            thumb.setImageResource(R.drawable.ic_document);
            thumb.setBackground(ui.panel(ScanUi.SURFACE, 8, ScanUi.LINE));
            thumb.setClipToOutline(true);
            item.addView(thumb, new LinearLayout.LayoutParams(ui.dp(65), ui.dp(82)));
            TextView caption = ui.text("第 " + number + " 页", 12, ScanUi.SECONDARY);
            caption.setPadding(0, ui.dp(5), 0, 0);
            item.addView(caption);
            loadImage(document, page, false, true, bitmap -> {
                if (bitmap != null) thumb.setImageBitmap(bitmap);
            });
            item.setContentDescription("编辑第 " + number + " 页");
            item.setFocusable(true);
            item.setOnClickListener(v -> {
                selectedPage = page.id;
                editFromDetail(false);
            });
            LinearLayout.LayoutParams itemWidth = new LinearLayout.LayoutParams(ui.wrap(), ui.wrap());
            itemWidth.setMarginEnd(ui.dp(9));
            thumbnails.addView(item, itemWidth);
        }
        strip.addView(thumbnails);
        body.addView(strip);
        TextView reviewsHeading = pageSectionHeading("识别结果");
        reviewsHeading.setPadding(0, ui.dp(15), 0, ui.dp(4));
        body.addView(reviewsHeading);
        boolean hasDraft = false;
        for (String key : REVIEW_KEYS) {
            try {
                org.json.JSONObject stored = store.loadReview(document, key);
                boolean older = store.reviewIsOutdated(document, key);
                if (stored == null && !older) continue;
                boolean table = ScanRecognition.EXCEL.equals(key) || ScanRecognition.HAND_TABLE.equals(key);
                boolean handwritten = ScanRecognition.HANDWRITING.equals(key) || ScanRecognition.HAND_TABLE.equals(key);
                String status = older ? " · 原图已更新" : "";
                if (stored != null && table) {
                    long pending = ScanRecognition.TableDraft.from(stored).tables.stream()
                            .flatMap(t -> t.cells.stream()).filter(c -> !c.reviewed).count();
                    if (pending > 0) status = " · " + pending + " 格待核对";
                }
                String review = (table ? "继续校对表格" : "查看文字稿")
                        + (handwritten ? "（手写）" : "（印刷）") + status;
                body.addView(actionTile(table ? R.drawable.ic_table : R.drawable.ic_text,
                        review, review, false, () -> openReview(key, table)));
                View spacing = new View(this);
                body.addView(spacing, new LinearLayout.LayoutParams(ui.match(), ui.dp(5)));
                hasDraft = true;
            } catch (IOException | org.json.JSONException unreadable) {
                body.addView(ui.text("有一份识别稿暂时无法读取", 14, ScanUi.SECONDARY));
            }
        }
        if (!hasDraft) body.addView(ui.text("提取文字或表格后，可在这里继续校对。", 14, ScanUi.SECONDARY));
        showSubsetDrafts(body, document.id);
        return layout;
    }

    private void actionPair(LinearLayout body, String first, Runnable left, String second, Runnable right) {
        LinearLayout row = ui.horizontal();
        for (Button button : new Button[]{ui.button(first, false, left), ui.button(second, false, right)}) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ui.wrap(), 1);
            params.setMargins(ui.dp(2), ui.dp(4), ui.dp(2), ui.dp(4));
            row.addView(button, params);
        }
        body.addView(row);
    }

    private void openReview(String key, boolean table) {
        reviewReturn = Screen.DETAIL;
        if (table) recognizeTables(key);
        else recognizeWord(key);
    }

    private void manageScanPages() {
        if (document.pages.isEmpty()) {
            addMenu();
            return;
        }
        ScanSheet sheet = new ScanSheet(this, ui, "管理扫描页");
        for (int i = 0; i < document.pages.size(); i++) {
            ScanDocument.Page page = document.pages.get(i);
            sheet.option(R.drawable.ic_document, "第 " + (i + 1) + " 页",
                    "移动、旋转或移除", page.id.equals(selectedPage), () -> {
                        selectedPage = page.id;
                        pageMenu();
                    });
        }
        sheet.option(R.drawable.ic_add, "添加页面", null, false, this::addMenu);
        sheet.option(R.drawable.ic_check, "完成", null, false, () -> {});
        sheet.show();
    }

    private View searchScreen() {
        searchView = new LibrarySearchView(this, ui, searchInput, this::navigateBack,
                new LibrarySearchView.Actions() {
                    public void search(String query, boolean index) { runSearch(query, index); }
                    public void open(DocumentSearch.Hit hit) {
                        openSearchHit(hit);
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

    private void openSearchHit(DocumentSearch.Hit hit) {
        task("打开搜索结果", false, state -> {
            ScanDocument source = store.load(hit.documentId);
            if (source == null) throw new IOException("无法打开这份扫描件");
            org.json.JSONObject json = hit.reviewKey == null ? null : store.loadReview(source, hit.reviewKey);
            ScanRecognition.WordDraft body = json != null && hit.tableIndex < 0
                    ? ScanRecognition.WordDraft.from(json) : null;
            ScanRecognition.TableDraft grid = json != null && hit.tableIndex >= 0
                    ? ScanRecognition.TableDraft.from(json) : null;
            return new SearchDestination(source, body, grid);
        }, result -> {
            open(result.source, hit.pageId);
            reviewReturn = Screen.SEARCH;
            if (result.body != null && !result.body.pages.isEmpty()) {
                wordDraft = result.body;
                wordReviewKey = hit.reviewKey;
                wordPage = Math.max(0, indexOf(hit.pageId));
                wordDirty = false;
                handwritingOriginal = true;
                screen = Screen.WORD;
                remember();
                render();
            } else if (result.grid != null && hit.tableIndex < result.grid.tables.size()) {
                tableReviewKey = hit.reviewKey;
                tableDraft = result.grid;
                tableIndex = hit.tableIndex;
                screen = Screen.EXCEL;
                remember();
                render();
                OfficeTable found = tableDraft.tables.get(tableIndex);
                if (hit.row >= 0 && hit.row < found.rows && hit.column >= 0 && hit.column < found.columns) {
                    OfficeTable.Cell cell = found.cell(hit.row, hit.column);
                    if (cell != null && tableGrid != null) editCell(cell, tableGrid);
                }
            } else if (hit.pageId != null) {
                editFromDetail(false);
            }
        }, null);
    }

    private static final class SearchDestination {
        final ScanDocument source;
        final ScanRecognition.WordDraft body;
        final ScanRecognition.TableDraft grid;

        SearchDestination(ScanDocument source, ScanRecognition.WordDraft body, ScanRecognition.TableDraft grid) {
            this.source = source;
            this.body = body;
            this.grid = grid;
        }
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
        String primary = batchEditing ? switch (batchAction) {
            case WORD, HANDWRITING -> "提取文字";
            case TABLE, HAND_TABLE -> "提取表格";
            case ID -> "预览证件";
            case QR -> "读取二维码";
            case EDIT, CROP -> "保存文档";
            default -> "预览效果";
        } : "保存修改";
        String summary = batchEditing ? switch (batchAction) {
            case WORD -> "文字 · 印刷";
            case HANDWRITING -> "文字 · 手写";
            case TABLE -> "表格 · 印刷";
            case HAND_TABLE -> "表格 · 手写或混合";
            case ID -> "证件复印件";
            case QR -> "扫码";
            default -> "扫描文件";
        } : null;
        editor.configureWorkflow(primary, summary, batchEditing);
        return editor;
    }

    private void handleEditor(EditorView.Action action) {
        if (job != null) return;
        switch (action) {
            case BACK: navigateBack(); break;
            case COMPLETE: finishEditor(); break;
            case CONTINUE_CAPTURE: if (saveCrop(true)) capture(); break;
            case MORE: imageToolsMenu(); break;
            case APPLY_CROP:
                if (saveCrop(true)) {
                    cropping = false;
                    pendingCrop = null;
                    remember();
                    render();
                }
                break;
            case CANCEL_CROP:
                cropping = false;
                pendingCrop = null;
                remember();
                render();
                break;
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
        if (batchEditing) cropping = true;
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
        sheet.option(R.drawable.ic_rotate, "旋转本页", null, false, this::rotate);
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
        if (batchEditing) {
            if (currentPage() == null) {
                toast("请先添加一页");
                return;
            }
            if (batchAction == ImageTool.ID && document.pages.size() != 2
                    || batchAction == ImageTool.QR && document.pages.size() != 1) {
                error(batchAction == ImageTool.ID ? "证件复印件需要正面和反面各一页" : "扫码需要一张图片",
                        this::pageMenu);
                return;
            }
            ImageTool action = batchAction;
            reviewReturn = Screen.DETAIL;
            switch (action) {
                case WORD -> { recognizeWord(ScanRecognition.WORD); return; }
                case HANDWRITING -> { recognizeWord(ScanRecognition.HANDWRITING); return; }
                case TABLE -> { recognizeTables(ScanRecognition.EXCEL); return; }
                case HAND_TABLE -> { recognizeTables(ScanRecognition.HAND_TABLE); return; }
                default -> { }
            }
            clearBatch();
            reviewReturn = Screen.DETAIL;
            screen = Screen.DETAIL;
            remember();
            render();
            switch (action) {
                case ID -> {
                    selectedPage = document.pages.get(0).id;
                    idSheet();
                }
                default -> runImageTool(action);
            }
            return;
        }
        cropping = false;
        pendingCrop = null;
        screen = editorReturn;
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
        String key = owner.id + ":" + owner.revision + "/" + page.id + ":" + page.rotation + ":" + original + ":" + thumb;
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
        sheet.option(R.drawable.ic_camera, "拍照扫描", null, false, () -> {
            if (batchEditing) capture();
            else startScanCamera();
        });
        sheet.option(R.drawable.ic_gallery, "从相册导入", "可一次选择多张", false, this::pick);
        sheet.show();
    }

    private void pick() {
        int existing = appendToCurrent() ? document.pages.size() : 0;
        int remaining = ScanStore.PAGE_LIMIT - existing;
        ImageTool action = batchEditing ? batchAction : nextImageTool;
        if (action == ImageTool.ID) remaining = Math.min(remaining, 2 - existing);
        if (action == ImageTool.QR) remaining = Math.min(remaining, 1 - existing);
        if (remaining <= 0) {
            boolean fromCapture = pickingForCapture;
            pickingForCapture = false;
            toast("当前批次无法再添加图片");
            if (fromCapture && existing < ScanStore.PAGE_LIMIT) capture();
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
            boolean fromCapture = pickingForCapture;
            pickingForCapture = false;
            error("此设备没有可用的图片选择器", fromCapture ? this::capture : null);
        }
    }

    private void startScanCamera() {
        startCamera(ImageTool.EDIT);
    }

    private void startCamera(ImageTool action) {
        nextImageTool = action;
        captureMode = switch (action) {
            case ID -> "id";
            case QR -> "qr";
            case WORD, HANDWRITING -> "text";
            case TABLE, HAND_TABLE -> "table";
            default -> "file";
        };
        captureHandwriting = action == ImageTool.HANDWRITING || action == ImageTool.HAND_TABLE;
        capture();
    }

    private boolean appendToCurrent() {
        return (screen == Screen.EDITOR || screen == Screen.DETAIL) && document != null;
    }

    private void capture() {
        if (preferences.contains(INCOMING_FILES)) {
            offerCaptureRecovery();
            return;
        }
        int existing = appendToCurrent() ? document.pages.size() : 0;
        int remaining = ScanStore.PAGE_LIMIT - existing;
        if (remaining <= 0) {
            toast("一份扫描件最多 " + ScanStore.PAGE_LIMIT + " 页");
            return;
        }
        Intent intent = new Intent(this, CaptureActivity.class)
                .putExtra(CaptureActivity.EXTRA_LIMIT, remaining)
                .putExtra(CaptureActivity.EXTRA_WORK_MODE, captureMode)
                .putExtra(CaptureActivity.EXTRA_HANDWRITING, captureHandwriting)
                .putExtra(CaptureActivity.EXTRA_INITIAL_PAGE_COUNT, existing);
        if (existing > 0) {
            intent.putExtra(CaptureActivity.EXTRA_EXISTING_THUMBNAIL,
                    store.image(document, document.pages.get(existing - 1)).getAbsolutePath());
        }
        camera.launch(intent);
    }

    private void parkCaptureInTools() {
        if (toolsResumeCapture) {
            ScanSheet sheet = new ScanSheet(this, ui, "已有待继续的拍摄");
            sheet.detail(appendToCurrent() ? "先前批次的返回入口已保留。当前图片可在文件中打开。"
                    : "先前批次的返回入口已保留。");
            sheet.option(R.drawable.ic_tools, "打开工具", null, false, this::showTools);
            sheet.option(R.drawable.ic_camera, "继续当前拍摄", null, false, this::capture);
            sheet.option(R.drawable.ic_back, "返回先前拍摄", null, false, this::resumeParkedCapture);
            sheet.show();
            return;
        }
        toolsResumeCapture = true;
        parkedBatchId = appendToCurrent() && document != null ? document.id : null;
        parkedMode = captureMode;
        parkedHandwriting = captureHandwriting;
        parkedAction = batchEditing ? batchAction : nextImageTool;
        parkedReturn = screen;
        parkedEditorReturn = editorReturn;
        showTools();
    }

    private void resumeParkedCapture() {
        toolsResumeCapture = false;
        captureMode = parkedMode;
        captureHandwriting = parkedHandwriting;
        nextImageTool = parkedAction;
        screen = parkedReturn;
        clearBatch();
        if (parkedBatchId != null) {
            try {
                document = store.load(parkedBatchId);
                if (document == null) throw new IOException("批次不存在");
            } catch (IOException unavailable) {
                document = null;
                screen = Screen.LIBRARY;
                render();
                error("无法恢复相机批次，可以在文件中重新打开扫描件", null);
                return;
            }
            screen = Screen.EDITOR;
            batchEditing = true;
            batchAction = parkedAction;
            cropping = true;
            selectedPage = document.pages.isEmpty() ? null : document.pages.get(document.pages.size() - 1).id;
            editorReturn = parkedEditorReturn;
            remember();
        }
        render();
        capture();
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
            boolean fromCapture = pickingForCapture;
            pickingForCapture = false;
            if (!chosen.isEmpty()) {
                importPhotos(chosen, new ArrayList<>(), fromCapture, null);
            } else if (fromCapture) {
                render();
                capture();
            } else {
                nextImageTool = ImageTool.EDIT;
            }
        }
    }

    private void receiveCamera(int result, Intent data) {
        if (result != RESULT_OK || data == null) {
            nextImageTool = ImageTool.EDIT;
            return;
        }
        String returnedMode = data.getStringExtra(CaptureActivity.EXTRA_WORK_MODE);
        if (returnedMode != null) captureMode = returnedMode;
        captureHandwriting = data.getBooleanExtra(CaptureActivity.EXTRA_HANDWRITING, captureHandwriting);
        ImageTool requested = batchEditing ? batchAction : nextImageTool;
        nextImageTool = switch (captureMode) {
            case "text" -> captureHandwriting ? ImageTool.HANDWRITING : ImageTool.WORD;
            case "table" -> captureHandwriting ? ImageTool.HAND_TABLE : ImageTool.TABLE;
            case "id" -> ImageTool.ID;
            case "qr" -> ImageTool.QR;
            default -> switch (requested) {
                case WORD, HANDWRITING, TABLE, HAND_TABLE, ID, QR -> ImageTool.EDIT;
                default -> requested;
            };
        };
        String shortcut = data.getStringExtra(CaptureActivity.EXTRA_DESTINATION);
        Runnable after = afterCaptureShortcut(shortcut);

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
        if (!inputs.isEmpty()) {
            importingShortcut = shortcut;
            importPhotos(inputs, photos, true, after);
            return;
        }
        if (batchEditing) batchAction = nextImageTool;
        if (after != null) {
            after.run();
        } else if (appendToCurrent() && (names == null || names.isEmpty())) {
            batchAction = nextImageTool;
            nextImageTool = ImageTool.EDIT;
            batchEditing = true;
            cropping = true;
            pendingCrop = null;
            if (screen == Screen.DETAIL) editorReturn = Screen.DETAIL;
            screen = Screen.EDITOR;
            remember();
            render();
        } else {
            error("相机没有返回完整图片，请重拍", this::capture);
        }
    }

    private Runnable afterCaptureShortcut(String shortcut) {
        if (CaptureActivity.DEST_ALBUM.equals(shortcut)) return () -> {
            pickingForCapture = true;
            pick();
        };
        if (CaptureActivity.DEST_TOOLS.equals(shortcut)) return this::parkCaptureInTools;
        if (CaptureActivity.DEST_PDF.equals(shortcut)) return () -> openPdf(PdfTool.ADD);
        return null;
    }

    private void offerCaptureRecovery() {
        new AlertDialog.Builder(this).setTitle("待导入的拍摄批次")
                .setMessage("已拍原图保存在本机。可以继续导入，或稍后从页面顶部恢复。")
                .setPositiveButton("继续导入", (dialog, which) -> recoverCapture())
                .setNeutralButton("稍后", null)
                .setNegativeButton("放弃待导入照片", (dialog, which) -> confirmDiscardCapture()).show();
    }

    private void confirmDiscardCapture() {
        new AlertDialog.Builder(this).setTitle("放弃待导入的照片？")
                .setMessage("尚未加入文档的照片将被删除。")
                .setNegativeButton("取消", null)
                .setPositiveButton("放弃", (dialog, which) -> {
                    for (File file : incomingFiles(preferences.getString(INCOMING_FILES, ""))) file.delete();
                    preferences.edit().remove(INCOMING_FILES).apply();
                    render();
                }).show();
    }

    private List<File> incomingFiles(String names) {
        List<File> files = new ArrayList<>();
        File directory = new File(getFilesDir(), "incoming");
        for (String name : names.split(",")) {
            if (!name.endsWith(".jpg") || !ScanStore.validId(name.substring(0, name.length() - 4))) continue;
            File file = new File(directory, name);
            if (file.isFile()) files.add(file);
        }
        return files;
    }

    private void recoverCapture() {
        List<File> captured = incomingFiles(preferences.getString(INCOMING_FILES, ""));
        if (captured.isEmpty()) {
            preferences.edit().remove(INCOMING_FILES).apply();
            render();
            return;
        }
        String id = preferences.getString("pendingCapture.id", "");
        nextImageTool = ImageTool.valueOf(preferences.getString("pendingCapture.action", ImageTool.EDIT.name()));
        captureMode = preferences.getString("pendingCapture.mode", "file");
        captureHandwriting = preferences.getBoolean("pendingCapture.handwriting", false);
        importingShortcut = preferences.getString("pendingCapture.shortcut", null);
        List<Uri> inputs = new ArrayList<>();
        for (File file : captured) inputs.add(uri(file));
        try {
            ScanDocument saved = store.load(id);
            if (saved != null) {
                if (document == null || !document.id.equals(id)) detailReturn = screen;
                document = saved;
                screen = Screen.DETAIL;
            }
            importPhotos(inputs, captured, true, afterCaptureShortcut(importingShortcut), id);
        } catch (IOException unreadable) {
            error("无法读取待接续的扫描件", this::recoverCapture);
        }
    }

    private void stageCapture(List<File> files, ScanDocument destination, ImageTool action) {
        List<String> names = new ArrayList<>();
        for (File file : files) names.add(file.getName());
        preferences.edit().putString(INCOMING_FILES, String.join(",", names))
                .putString("pendingCapture.id", destination.id)
                .putString("pendingCapture.action", action.name())
                .putString("pendingCapture.mode", captureMode)
                .putBoolean("pendingCapture.handwriting", captureHandwriting)
                .putString("pendingCapture.shortcut", importingShortcut).commit();
    }

    private static String capturedPageId(File file) {
        String name = file.getName();
        return name.substring(0, name.length() - 4);
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

    private void importPhotos(List<Uri> inputs, List<File> captured, boolean fromCamera, Runnable after) {
        importPhotos(inputs, captured, fromCamera, after, null);
    }

    private void importPhotos(List<Uri> inputs, List<File> captured, boolean fromCamera, Runnable after,
            String recoveredId) {
        ImageTool action = fromCamera || !batchEditing ? nextImageTool : batchAction;
        ScanDocument.Filter initialFilter = switch (action) {
            case SCREEN -> ScanDocument.Filter.SCREEN;
            case EDIT, ENHANCE, WORD, TABLE, CROP, BOOK, FLATTEN -> ScanDocument.Filter.AUTO;
            default -> ScanDocument.Filter.ORIGINAL;
        };
        nextImageTool = ImageTool.EDIT;
        boolean append = appendToCurrent() && (recoveredId == null || document.id.equals(recoveredId));
        boolean prepareBatch = fromCamera || batchEditing || action == ImageTool.EDIT;
        Screen returnScreen = screen;
        ScanDocument allocated = append ? document : store.newDocument();
        ScanDocument fresh = recoveredId == null || append ? allocated
                : new ScanDocument(recoveredId, allocated.name, allocated.modifiedAt, allocated.revision,
                        allocated.pages, allocated.folder, allocated.titleSource);
        ScanDocument destination = !append && action == ImageTool.ID
                ? fresh.filed(ScanDocument.Folder.IDS) : fresh;
        if (!captured.isEmpty()) stageCapture(captured, destination, action);
        Set<String> existingIds = new LinkedHashSet<>();
        for (ScanDocument.Page page : destination.pages) existingIds.add(page.id);
        int newPages = inputs.size();
        for (File file : captured) if (existingIds.contains(capturedPageId(file))) newPages--;
        int room = ScanStore.PAGE_LIMIT - destination.pages.size();
        if (captured.isEmpty() && action == ImageTool.ID) room = Math.min(room, 2 - destination.pages.size());
        if (captured.isEmpty() && action == ImageTool.QR) room = Math.min(room, 1 - destination.pages.size());
        if (newPages > Math.max(0, room)) {
            render();
            error("只能再加入 " + Math.max(0, room) + " 页，请减少所选图片张数", null);
            return;
        }
        task("导入图片", true, state -> {
            List<ScanDocument.Page> pages = new ArrayList<>(destination.pages);
            List<ScanDocument.Page> added = new ArrayList<>();
            List<Integer> failed = new ArrayList<>();
            List<File> accepted = new ArrayList<>();
            Uri firstImported = null;
            boolean saved = false;
            try {
                for (int i = 0; i < inputs.size(); i++) {
                    state.check();
                    File staged = captured.isEmpty() ? null : captured.get(i);
                    String pageId = staged == null ? ScanStore.newId() : capturedPageId(staged);
                    if (staged != null && existingIds.contains(pageId)) {
                        accepted.add(staged);
                        continue;
                    }
                    ScanDocument.Page page = new ScanDocument.Page(pageId, 0, CropQuad.FULL, initialFilter);
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
                        if (staged != null) accepted.add(staged);
                        if (firstImported == null) firstImported = inputs.get(i);
                    } catch (IOException | RuntimeException invalid) {
                        failed.add(i + 1);
                    } finally {
                        if (!kept) store.removeSource(destination, page);
                        if (bitmap != null) bitmap.recycle();
                    }
                    state.progress(i + 1, inputs.size());
                }
                if (added.isEmpty() && accepted.isEmpty()) {
                    throw new IOException("没有图片成功导入，请检查文件格式或访问权限");
                }
                ScanDocument merged = added.isEmpty() ? destination : destination.withPages(pages);
                if (!append && captured.isEmpty()) merged = importTitle(merged, firstImported);
                state.check();
                if (!added.isEmpty()) store.save(merged);
                saved = true;
                if (!captured.isEmpty()) {
                    List<File> remaining = new ArrayList<>(captured);
                    remaining.removeAll(accepted);
                    if (remaining.isEmpty()) preferences.edit().remove(INCOMING_FILES).commit();
                    else stageCapture(remaining, destination, action);
                }
                List<ScanDocument.Page> selection = added.isEmpty() ? pages : added;
                return new ImportResult(merged, selection.get(fromCamera ? selection.size() - 1 : 0).id, failed);
            } finally {
                if (!saved) for (ScanDocument.Page page : added) store.removeSource(destination, page);
                if (saved) for (File file : accepted) file.delete();
            }
        }, imported -> {
            document = imported.document;
            if (!append) {
                detailReturn = returnScreen;
                editorReturn = Screen.DETAIL;
            } else if (returnScreen == Screen.DETAIL) {
                editorReturn = Screen.DETAIL;
            }
            selectedPage = imported.first;
            pendingCrop = null;
            reviewReturn = Screen.DETAIL;
            batchEditing = prepareBatch;
            batchAction = prepareBatch ? action : ImageTool.EDIT;
            cropping = prepareBatch || action == ImageTool.CROP;
            if (!fromCamera && !append && prepareBatch) {
                captureMode = "file";
                captureHandwriting = false;
            }
            screen = prepareBatch || action == ImageTool.CROP ? Screen.EDITOR : Screen.DETAIL;
            remember();
            render();
            if (!imported.failed.isEmpty()) toast("以下图片无法导入：" + joined(imported.failed));
            if (after != null && !preferences.contains(INCOMING_FILES)) {
                after.run();
            } else if (!prepareBatch) {
                runImageTool(action);
            }
        }, captured.isEmpty() ? null : this::recoverCapture);
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
        if (action == PdfTool.OPEN) {
            screen = Screen.PDF;
            render();
        } else if (action == PdfTool.ADD) {
            pickPdf(PdfTool.OPEN);
        } else if (pdfPages.isEmpty()) {
            pickPdf(action);
        } else {
            choosePdfSource(action);
        }
    }

    private void choosePdfSource(PdfTool action) {
        ScanSheet sheet = new ScanSheet(this, ui, "选择 PDF 文件");
        int count = selectedPdfPages().size();
        sheet.option(R.drawable.ic_pdf, "使用工作台", "当前已选 " + count + " 页",
                false, () -> {
                    screen = Screen.PDF;
                    render();
                    runPdfTool(action);
                });
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
        String title = pdfPages.isEmpty() ? "PDF 阅读" : pdfPages.get(
                Math.max(0, Math.min(pdfPage, pdfPages.size() - 1))).name;
        layout.addView(ui.header(title, this::navigateBack,
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
        footer.addView(ui.button("管理页面", false, this::managePdfPages), left);
        footer.addView(ui.button("转换", false, this::pdfConversionMenu),
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

    private void pdfConversionMenu() {
        ScanSheet sheet = new ScanSheet(this, ui, "转换 PDF");
        sheet.option(R.drawable.ic_text, "提取文字", "校对正文，可复制或导出 Word", false,
                () -> runPdfTool(PdfTool.WORD));
        sheet.option(R.drawable.ic_table, "提取表格", "校对表格后导出 Excel", false,
                () -> runPdfTool(PdfTool.EXCEL));
        sheet.show();
    }

    private void pdfExportMenu() {
        int count = selectedPdfPages().size();
        ScanSheet sheet = new ScanSheet(this, ui, "导出 PDF 工作台");
        sheet.detail("已选 " + count + " 页。PDF 页面副本保留正文和图形。原批注、表单和数字签名请在原文件查看。");
        sheet.option(R.drawable.ic_pdf, "导出所选 PDF", "按当前页序合为一份", false,
                () -> runPdfTool(PdfTool.SAVE));
        sheet.option(R.drawable.ic_crop, "按页拆分", "每页各生成一份 PDF，保存为 ZIP", false,
                () -> runPdfTool(PdfTool.SPLIT));
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
            case TEXT:
                choosePdfRecognition(picked, title, false);
                break;
            case EXCEL:
                choosePdfRecognition(picked, title, true);
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
        ScanSheet sheet = new ScanSheet(this, ui, "选择材料 · " + label);
        if (appendToCurrent() && currentPage() != null) {
            sheet.option(R.drawable.ic_document, "使用当前扫描件", document.name, false, () -> runImageTool(action));
        }
        sheet.option(R.drawable.ic_document, "从已有扫描件选择", null, false,
                () -> chooseScanForTool(label, action));
        sheet.option(R.drawable.ic_camera, "拍照扫描", null, false, () -> startCamera(action));
        sheet.option(R.drawable.ic_gallery, "从相册导入", null, false, () -> {
            nextImageTool = action;
            pick();
        });
        if (action == ImageTool.WORD || action == ImageTool.TABLE) {
            sheet.option(R.drawable.ic_pdf, "选择 PDF", null, false,
                    () -> openPdf(action == ImageTool.WORD ? PdfTool.WORD : PdfTool.EXCEL));
        }
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
            if (screen == Screen.DETAIL) editFromDetail(true);
            else if (!cropping) startCrop();
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
                chooseRecognitionMode(false);
                break;
            case HANDWRITING:
                recognizeWord(ScanRecognition.HANDWRITING);
                break;
            case TABLE:
                chooseRecognitionMode(true);
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
            case FLATTEN:
                flattenBookPage();
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
        sheet.option(R.drawable.ic_document, "书页处理", null, false, () -> bookTools(true));
        sheet.option(R.drawable.ic_text, "提取文字", null, false, () -> chooseRecognitionMode(false));
        sheet.option(R.drawable.ic_table, "提取表格", null, false, () -> chooseRecognitionMode(true));
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

    private void flattenBookPage() {
        ScanDocument current = document;
        ScanDocument.Page page = currentPage();
        if (page.isFlattened()) {
            new AlertDialog.Builder(this).setTitle("恢复展平前？")
                    .setMessage("将恢复原稿及展平前的裁切、方向和画质设置。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("恢复原稿", (d, which) -> restoreBookPage(current, page)).show();
            return;
        }
        task("展平当前书页", true, state -> {
            Bitmap source = ScanImages.render(store.image(current, page),
                    page.filter(ScanDocument.Filter.ORIGINAL), 3000);
            Bitmap output = null, before = null, after = null;
            File temporary = null;
            boolean transferred = false;
            try (BookDewarp reader = new BookDewarp(getAssets())) {
                BookDewarp.Result result = reader.flatten(source, state::check);
                output = result.image;
                state.check();
                if (!result.changed()) return new BookFlatteningPreview(null, null, null);
                temporary = File.createTempFile("book-flat-", ".png", getCacheDir());
                try (OutputStream stream = new BufferedOutputStream(new FileOutputStream(temporary))) {
                    if (!output.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw new IOException("无法生成展平书页");
                }
                state.check();
                before = BookFlatteningPreview.thumbnail(source);
                after = BookFlatteningPreview.thumbnail(output);
                state.check();
                BookFlatteningPreview preview = new BookFlatteningPreview(before, after, temporary);
                transferred = true;
                return preview;
            } finally {
                if (output != null && output != source) output.recycle();
                source.recycle();
                if (!transferred) {
                    if (before != null) before.recycle();
                    if (after != null) after.recycle();
                    if (temporary != null) temporary.delete();
                }
            }
        }, preview -> {
            if (!preview.changed()) {
                preview.close();
                toast("展平结果与原稿接近，已保留原稿");
                return;
            }
            showBookPreview(current, page, preview);
        }, this::flattenBookPage);
    }

    private void showBookPreview(ScanDocument current, ScanDocument.Page page, BookFlatteningPreview result) {
        ScanPreview preview = new ScanPreview(this, ui);
        LinearLayout panel = new LinearLayout(this) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                super.onMeasure(widthSpec, heightSpec);
                if (MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED) return;
                int other = getPaddingTop() + getPaddingBottom();
                for (int i = 0; i < getChildCount(); i++) {
                    View child = getChildAt(i);
                    if (child != preview) other += child.getMeasuredHeight();
                }
                // AlertDialog allocates its custom panel after title and action buttons.
                // Recompute on each layout so a portrait preview also fits a short landscape window.
                int available = Math.max(1, MeasureSpec.getSize(heightSpec) - other);
                preview.getLayoutParams().height = Math.min(ui.dp(430), available);
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        panel.setOrientation(LinearLayout.VERTICAL);
        TextView label = ui.text("检查页边和字行，确认后应用", 14, ScanUi.SECONDARY);
        label.setPadding(0, 0, 0, ui.dp(10));
        panel.addView(label);
        panel.addView(preview, new LinearLayout.LayoutParams(ui.match(), ui.dp(430)));
        LinearLayout modes = ui.horizontal();
        Button before = ui.quietButton("展平前", ScanUi.INK, () -> {});
        Button after = ui.quietButton("展平后", ScanUi.ACCENT, () -> {});
        java.util.function.Consumer<Boolean> compare = showBefore -> {
            preview.show(showBefore ? result.before : result.after, null, null, true);
            before.setSelected(showBefore);
            after.setSelected(!showBefore);
            before.setTextColor(showBefore ? ScanUi.ACCENT : ScanUi.INK);
            after.setTextColor(showBefore ? ScanUi.INK : ScanUi.ACCENT);
        };
        before.setOnClickListener(v -> compare.accept(true));
        after.setOnClickListener(v -> compare.accept(false));
        after.setSelected(true);
        modes.addView(before, new LinearLayout.LayoutParams(0, ui.dp(48), 1));
        modes.addView(after, new LinearLayout.LayoutParams(0, ui.dp(48), 1));
        panel.addView(modes, new LinearLayout.LayoutParams(ui.match(), ui.dp(48)));
        preview.show(result.after, null, null);
        preview.setContentDescription("书页展平对比预览，可缩放");
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("书页展平")
                .setView(inset(panel)).setNegativeButton("取消", null).setPositiveButton("应用展平", null).create();
        bookPreviewDialog = dialog;
        dialog.setOnDismissListener(d -> {
            preview.show(null, null, null);
            result.close();
            if (bookPreviewDialog == dialog) bookPreviewDialog = null;
        });
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v ->
                task("保存展平书页", false, state -> {
                    store.writeFlattened(current, page, result.flattened, state::check);
                    state.check();
                    List<ScanDocument.Page> pages = new ArrayList<>(current.pages);
                    pages.set(current.pages.indexOf(page), page.flattened());
                    ScanDocument updated = current.withPages(pages);
                    store.save(updated);
                    return updated;
                }, updated -> {
                    document = updated;
                    cropping = false;
                    pendingCrop = null;
                    remember();
                    images.evictAll();
                    contentGeneration++;
                    dialog.dismiss();
                    render();
                    toast("已应用展平，原稿已保留");
                }, null)));
        dialog.show();
    }

    private void restoreBookPage(ScanDocument current, ScanDocument.Page page) {
        task("恢复书页原稿", false, state -> {
            List<ScanDocument.Page> pages = new ArrayList<>(current.pages);
            pages.set(current.pages.indexOf(page), page.restoreFlattening());
            ScanDocument updated = current.withPages(pages);
            store.save(updated);
            store.discardFlattened(current, page);
            return updated;
        }, updated -> {
            document = updated;
            cropping = false;
            pendingCrop = null;
            remember();
            images.evictAll();
            contentGeneration++;
            render();
            toast("已恢复展平前的原稿");
        }, null);
    }

    private void editImage(String suffix, BitmapEdit edit) {
        ScanDocument current = document;
        ScanDocument.Page page = currentPage();
        task("生成预览", true, state -> {
            Bitmap input = ScanImages.render(store.image(current, page), page, ScanImages.EXPORT_EDGE);
            return toolPreview(input, edit);
        }, images -> showToolPreview(suffix, "第 " + (indexOf(page.id) + 1) + " 页", images,
                "另存图片", () -> savePng(current.name + " - " + suffix, state -> {
                    Bitmap input = ScanImages.render(store.image(current, page), page, ScanImages.EXPORT_EDGE);
                    Bitmap output = null;
                    try {
                        output = edit.apply(input);
                        return output;
                    } finally {
                        if (output != input) input.recycle();
                    }
                })), null);
    }

    private static ToolPreview toolPreview(Bitmap input, BitmapEdit edit) throws IOException {
        Bitmap edited = null;
        Bitmap original = null;
        try {
            edited = edit.apply(input);
            original = previewScale(input);
            Bitmap result = input == edited ? original : previewScale(edited);
            return new ToolPreview(original, result);
        } catch (RuntimeException | IOException failure) {
            if (original != null) original.recycle();
            throw failure;
        } finally {
            if (input != original) input.recycle();
            if (edited != null && edited != input && Math.max(edited.getWidth(), edited.getHeight()) > 1500) {
                edited.recycle();
            }
        }
    }

    private static Bitmap previewScale(Bitmap source) {
        int edge = Math.max(source.getWidth(), source.getHeight());
        if (edge <= 1500) return source;
        return Bitmap.createScaledBitmap(source, Math.max(1, Math.round(source.getWidth() * 1500f / edge)),
                Math.max(1, Math.round(source.getHeight() * 1500f / edge)), true);
    }

    private static final class ToolPreview implements AutoCloseable {
        final Bitmap original;
        final Bitmap result;

        ToolPreview(Bitmap original, Bitmap result) {
            this.original = original;
            this.result = result;
        }

        @Override public void close() {
            if (original != null && original != result) original.recycle();
            result.recycle();
        }
    }

    private void showToolPreview(String title, String scope, ToolPreview images, String action, Runnable save) {
        LinearLayout body = ui.vertical();
        ScanPreview image = new ScanPreview(this, ui);
        body.addView(ui.text(scope, 14, ScanUi.SECONDARY));
        if (images.original != null) {
            actionPair(body, "原图", () -> image.show(images.original, null, null, true),
                    "处理结果", () -> image.show(images.result, null, null, true));
        }
        int height = Math.max(120, Math.min(430, getResources().getConfiguration().screenHeightDp - 260));
        body.addView(image, new LinearLayout.LayoutParams(ui.match(), ui.dp(height)));
        image.show(images.result, null, null);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setView(inset(body))
                .setNegativeButton("返回调整", null)
                .setPositiveButton(action, (d, which) -> save.run()).create();
        toolPreviewDialog = dialog;
        dialog.setOnDismissListener(d -> {
            image.show(null, null, null);
            images.close();
            if (toolPreviewDialog == dialog) toolPreviewDialog = null;
        });
        dialog.show();
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
        String scope = back == null ? "正面 · 第 " + (start + 1) + " 页"
                : "正面 · 第 " + (start + 1) + " 页，反面 · 第 " + (start + 2) + " 页";
        task("预览证件复印件", true,
                state -> new ToolPreview(null, renderIdSheet(current, front, back)),
                images -> showToolPreview("证件复印件", scope, images,
                        "保存 PDF", () -> prepareFile(FileFormat.PDF, current.name + " - 证件合页",
                                (stream, state) -> ScanExport.rasterPdf(1, index -> renderIdSheet(current, front, back),
                                        true, stream, state::progress))), null);
    }

    private Bitmap renderIdSheet(ScanDocument current, ScanDocument.Page front, ScanDocument.Page back) throws IOException {
        Bitmap first = ScanImages.render(store.image(current, front), front, 1350);
        Bitmap second = null;
        try {
            if (back != null) second = ScanImages.render(store.image(current, back), back, 1350);
            return LocalImages.idSheet(first, second);
        } finally {
            first.recycle();
            if (second != null) second.recycle();
        }
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
        sheet.show();
    }

    private void pdfFormats() {
        ScanSheet sheet = new ScanSheet(this, ui, "PDF 页面尺寸");
        boolean a4 = defaultA4();
        addPdfFormat(sheet, a4, true);
        addPdfFormat(sheet, !a4, false);
        sheet.show();
    }

    private void addPdfFormat(ScanSheet sheet, boolean a4, boolean preferred) {
        String detail = a4 ? "等比适配，方便打印" : "保留每页的画面比例";
        sheet.option(a4 ? R.drawable.ic_pdf : R.drawable.ic_image, a4 ? "A4 纸张" : "图片比例",
                preferred ? "默认 · " + detail : detail, preferred, () -> exportPdf(a4));
    }

    private void exportPdf(boolean a4) {
        ScanDocument current = document;
        prepareFile(FileFormat.PDF, current.name, (stream, state) ->
                ScanExport.pdf(store, current, a4, stream, state::progress));
    }

    private void imageFormats() {
        ScanSheet sheet = new ScanSheet(this, ui, "保存当前页");
        boolean png = defaultPng();
        addImageFormat(sheet, png, true);
        addImageFormat(sheet, !png, false);
        sheet.show();
    }

    private void addImageFormat(ScanSheet sheet, boolean png, boolean preferred) {
        String detail = png ? "无损保存，适合细线与文字" : "通用格式，文件较小";
        sheet.option(R.drawable.ic_image, png ? "PNG" : "JPG", preferred ? "默认 · " + detail : detail,
                preferred, () -> exportImage(png));
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
            org.json.JSONObject saved = store.loadReview(current, reviewKey);
            if (!rerun && saved != null) {
                return new DraftCandidate<>(ScanRecognition.WordDraft.from(saved), false);
            }
            boolean replacing = saved != null || store.reviewIsOutdated(current, reviewKey);
            ScanRecognition.WordDraft draft = handwriting
                    ? ScanRecognition.handwriting(getAssets(), store, current, state::progress)
                    : ScanRecognition.word(getAssets(), store, current, state::progress);
            if (replacing && draft.pages.stream().allMatch(page -> page.trim().isEmpty())) {
                throw new IOException("重新识别没有读到文字，已保留原校对稿");
            }
            state.check();
            if (!replacing) store.saveReview(current, reviewKey, draft.json());
            return new DraftCandidate<>(draft, replacing);
        }, candidate -> {
            Runnable open = () -> {
                wordDraft = candidate.draft;
                wordReviewKey = reviewKey;
                nameFromDraft(wordDraft);
                if (!rerun) handwritingOriginal = true;
                wordDirty = false;
                if (!rerun) wordPage = Math.max(0, indexOf(selectedPage));
                clearBatch();
                screen = Screen.WORD;
                remember();
                render();
            };
            if (candidate.replaces) {
                String excerpt = candidate.draft.pages.stream().filter(page -> !page.trim().isEmpty()).findFirst().orElse("");
                if (excerpt.length() > 420) excerpt = excerpt.substring(0, 420) + "…";
                offerDraftReplacement(current, reviewKey, "新识别稿 · " + candidate.draft.pages.size()
                        + " 页\n\n" + excerpt, candidate.draft::json, open);
            } else open.run();
        }, () -> loadWord(reviewKey, rerun));
    }

    private static final class DraftCandidate<T> {
        final T draft;
        final boolean replaces;

        DraftCandidate(T draft, boolean replaces) {
            this.draft = draft;
            this.replaces = replaces;
        }
    }

    private interface DraftJson {
        org.json.JSONObject get() throws org.json.JSONException;
    }

    private void offerDraftReplacement(ScanDocument source, String key, String preview, DraftJson json, Runnable open) {
        new AlertDialog.Builder(this).setTitle("使用新识别稿？")
                .setMessage(preview)
                .setNegativeButton("保留现有稿", null)
                .setPositiveButton("替换并校对", (dialog, which) -> task("保存新识别稿", false, state -> {
                    store.saveReview(source, key, json.get());
                    return true;
                }, done -> open.run(), null)).show();
    }

    private void recognizeTables(String key) {
        checkDraft(key, () -> loadTables(key, false));
    }

    private void chooseRecognitionMode(boolean table) {
        if (currentPage() == null || !saveCrop(true)) return;
        boolean[] selected = new boolean[document.pages.size()];
        java.util.Arrays.fill(selected, true);
        recognitionOptions(document, table, selected);
    }

    private void recognitionOptions(ScanDocument source, boolean table, boolean[] selected) {
        String title = table ? "提取表格" : "提取文字";
        String printedKey = table ? ScanRecognition.EXCEL : ScanRecognition.WORD;
        String handKey = table ? ScanRecognition.HAND_TABLE : ScanRecognition.HANDWRITING;
        int count = 0;
        for (boolean value : selected) if (value) count++;
        ScanSheet sheet = new ScanSheet(this, ui, title);
        sheet.detail(source.name + " · 已选 " + count + " / " + selected.length + " 页");
        if (selected.length > 1) {
            sheet.option(R.drawable.ic_crop, "调整选页", null, false,
                    () -> chooseScanRecognitionPages(source, table, selected));
        }
        if (count > 0) {
            sheet.option(R.drawable.ic_text, "印刷", "开始提取", false,
                    () -> recognizeSelected(source, table, selected, printedKey));
            sheet.option(R.drawable.ic_edit, table ? "手写或混合" : "手写", "开始提取", false,
                    () -> recognizeSelected(source, table, selected, handKey));
        }
        sheet.show();
    }

    private void chooseScanRecognitionPages(ScanDocument source, boolean table, boolean[] selected) {
        String[] pages = new String[selected.length];
        for (int i = 0; i < pages.length; i++) pages[i] = "第 " + originalPageNumber(source, i) + " 页";
        boolean[] edited = selected.clone();
        new AlertDialog.Builder(this).setTitle("选择要提取的扫描页")
                .setMultiChoiceItems(pages, edited, (dialog, index, checked) -> edited[index] = checked)
                .setNegativeButton("返回", (dialog, which) -> recognitionOptions(source, table, selected))
                .setOnCancelListener(dialog -> recognitionOptions(source, table, selected))
                .setPositiveButton("应用选页", (dialog, which) -> recognitionOptions(source, table, edited)).show();
    }

    private void recognizeSelected(ScanDocument source, boolean table, boolean[] selected, String key) {
        List<ScanDocument.Page> pages = new ArrayList<>();
        List<String> sourceNumbers = new ArrayList<>();
        for (int i = 0; i < selected.length; i++) {
            if (!selected[i]) continue;
            pages.add(source.pages.get(i));
            sourceNumbers.add(Integer.toString(originalPageNumber(source, i)));
        }
        if (pages.isEmpty()) return;
        reviewReturn = Screen.DETAIL;
        Runnable recognize = () -> {
            if (table) recognizeTables(key);
            else recognizeWord(key);
        };
        if (pages.size() == source.pages.size()) {
            recognize.run();
            return;
        }
        Screen origin = screen == Screen.EDITOR && editorReturn != Screen.DETAIL ? editorReturn : detailReturn;
        task("准备所选扫描页", true, state -> prepareScanSubset(source, pages, sourceNumbers, state), subset -> {
            screen = origin;
            open(subset);
            recognize.run();
        }, () -> recognizeSelected(source, table, selected, key));
    }

    private ScanDocument prepareScanSubset(ScanDocument source, List<ScanDocument.Page> pages,
            List<String> sourceNumbers, JobState state) throws IOException {
        StringBuilder key = new StringBuilder(SUBSET).append(source.id).append('/').append(source.revision);
        for (ScanDocument.Page page : pages) key.append('/').append(page.id);
        String existing = preferences.getString(key.toString(), null);
        ScanDocument saved = existing == null ? null : store.load(existing);
        List<String> originalPositions = new ArrayList<>();
        for (int i = 0; i < pages.size(); i++) {
            originalPositions.add(pages.get(i).id + ":" + sourceNumbers.get(i));
        }
        String mapping = String.join(",", originalPositions);
        if (saved != null && source.id.equals(preferences.getString(SUBSET_SOURCE + saved.id, null))
                && matchesScanSubset(saved.pages, pages, preferences.getString(SUBSET_PAGES + saved.id, null), mapping)) {
            return saved;
        }
        ScanDocument subset = store.newDocument().renamed(source.name + " · 选页").filed(source.folder).withPages(pages);
        List<ScanDocument.Page> copied = new ArrayList<>();
        boolean committed = false;
        try {
            for (int i = 0; i < pages.size(); i++) {
                state.check();
                ScanDocument.Page page = pages.get(i);
                copied.add(page);
                if (page.beforeFlatten != null) {
                    copyScanPage(store.image(source, page.beforeFlatten), store.image(subset, page.beforeFlatten));
                }
                copyScanPage(store.image(source, page), store.image(subset, page));
                state.progress(i + 1, pages.size());
            }
            state.check();
            store.save(subset);
            preferences.edit().putString(key.toString(), subset.id)
                    .putString(SUBSET_SOURCE + subset.id, source.id)
                    .putString(SUBSET_PAGES + subset.id, mapping).apply();
            committed = true;
            return subset;
        } finally {
            if (!committed) for (ScanDocument.Page page : copied) store.removeSource(subset, page);
        }
    }

    static boolean matchesScanSubset(List<ScanDocument.Page> saved, List<ScanDocument.Page> selected,
            String savedMapping, String expectedMapping) {
        if (saved.size() != selected.size() || !expectedMapping.equals(savedMapping)) return false;
        for (int i = 0; i < selected.size(); i++) {
            if (!selected.get(i).id.equals(saved.get(i).id)) return false;
        }
        return true;
    }

    private static void copyScanPage(File source, File destination) throws IOException {
        ScanStore.ensureDirectory(destination.getParentFile());
        try {
            android.system.Os.link(source.getAbsolutePath(), destination.getAbsolutePath());
        } catch (android.system.ErrnoException unavailable) {
            java.nio.file.Files.copy(source.toPath(), destination.toPath());
        }
    }

    private int originalPageNumber(ScanDocument owner, int position) {
        String stored = preferences.getString(SUBSET_PAGES + owner.id, null);
        if (stored == null || position < 0 || position >= owner.pages.size()) return position + 1;
        String prefix = owner.pages.get(position).id + ":";
        for (String mapping : stored.split(",")) {
            if (mapping.startsWith(prefix)) return Integer.parseInt(mapping.substring(prefix.length()));
        }
        return position + 1;
    }

    private void openSubsetSource(String id) {
        try {
            ScanDocument source = store.load(id);
            if (source == null) error("关联的原扫描件已移除", null);
            else open(source);
        } catch (IOException unreadable) {
            error("无法打开关联的原扫描件", null);
        }
    }

    private void showSubsetDrafts(LinearLayout body, String id) {
        Set<String> children = new LinkedHashSet<>();
        for (java.util.Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            if (entry.getKey().startsWith(SUBSET_SOURCE) && id.equals(entry.getValue())) {
                children.add(entry.getKey().substring(SUBSET_SOURCE.length()));
            }
        }
        for (String child : children) {
            try {
                ScanDocument result = store.load(child);
                if (result == null || !hasReview(result)) continue;
                List<String> numbers = new ArrayList<>();
                for (int i = 0; i < result.pages.size(); i++) {
                    numbers.add(Integer.toString(originalPageNumber(result, i)));
                }
                body.addView(ui.quietButton("选页稿 · 原第 " + String.join("、", numbers) + " 页",
                        ScanUi.INK, () -> open(result)));
            } catch (IOException unreadable) {
                // The linked result can still be located in the file list if recovered.
            }
        }
    }

    private void choosePdfRecognition(List<PdfWorkspace.Page> picked, String title, boolean table) {
        ScanSheet sheet = new ScanSheet(this, ui, table ? "提取表格" : "提取文字");
        sheet.detail(title + " · 已选 " + picked.size() + " 页");
        sheet.option(R.drawable.ic_crop, "调整选页", null, false,
                () -> managePdfPages(table ? PdfTool.EXCEL : PdfTool.WORD));
        sheet.option(R.drawable.ic_text, "印刷", "开始提取", false,
                () -> openPdfReview(picked, title, table, false));
        sheet.option(R.drawable.ic_edit, table ? "手写或混合" : "手写", "开始提取", false,
                () -> openPdfReview(picked, title, table, true));
        sheet.show();
    }

    private void openPdfReview(List<PdfWorkspace.Page> pages, String title, boolean table, boolean hand) {
        if (pages.size() > ScanStore.PAGE_LIMIT) {
            error("一份识别稿最多 " + ScanStore.PAGE_LIMIT + " 页，请减少所选页面", this::managePdfPages);
            return;
        }
        String mode = table ? (hand ? ScanRecognition.HAND_TABLE : ScanRecognition.EXCEL)
                : (hand ? ScanRecognition.HANDWRITING : ScanRecognition.WORD);
        String fingerprint = pdfReviewKey(pages);
        task(table ? "提取 PDF 表格" : "提取 PDF 文字", true, state -> {
            String id = preferences.getString(fingerprint, null);
            ScanDocument copy = id == null ? null : store.load(id);
            if (copy == null) {
                copy = copyPdfReviewSource(pages, title, state);
                preferences.edit().putString(fingerprint, copy.id)
                        .putString("pdfReviewSource/" + copy.id, title).apply();
            }
            if (store.loadReview(copy, mode) == null && !store.reviewIsOutdated(copy, mode)) {
                if (table) {
                    List<OfficeTable> tables = PdfTools.tables(pdfStore, pages, getAssets(), state::progress, hand);
                    ScanRecognition.TableDraft draft = new ScanRecognition.TableDraft(tables, java.util.Collections.emptyList()).checked(mode);
                    state.check();
                    store.saveReview(copy, mode, draft.json());
                } else if (!hand) {
                    List<String> words = PdfTools.text(pdfStore, pages, getAssets(), state::progress);
                    ScanRecognition.WordDraft draft = new ScanRecognition.WordDraft(words);
                    state.check();
                    store.saveReview(copy, mode, draft.json());
                }
            }
            return copy;
        }, copy -> {
            document = copy;
            selectedPage = copy.pages.get(0).id;
            detailReturn = Screen.PDF;
            reviewReturn = Screen.DETAIL;
            screen = Screen.DETAIL;
            remember();
            render();
            if (table) recognizeTables(mode);
            else recognizeWord(mode);
        }, () -> openPdfReview(pages, title, table, hand));
    }

    private static String pdfReviewKey(List<PdfWorkspace.Page> pages) {
        StringBuilder key = new StringBuilder("pdfReview:");
        for (PdfWorkspace.Page page : pages) {
            key.append(page.source).append('/').append(page.index).append('/').append(page.turns).append(';');
        }
        return key.toString();
    }

    private ScanDocument copyPdfReviewSource(List<PdfWorkspace.Page> pages, String title, JobState state)
            throws IOException {
        ScanDocument copy = store.newDocument().renamed(title + " · PDF 校对");
        List<ScanDocument.Page> added = new ArrayList<>();
        boolean saved = false;
        try {
            for (int i = 0; i < pages.size(); i++) {
                state.check();
                ScanDocument.Page target = new ScanDocument.Page(ScanStore.newId(), 0,
                        CropQuad.FULL, ScanDocument.Filter.ORIGINAL);
                File file = store.image(copy, target);
                ScanStore.ensureDirectory(file.getParentFile());
                added.add(target);
                Bitmap bitmap = PdfTools.render(pdfStore, pages.get(i), ScanImages.EXPORT_EDGE);
                try (OutputStream output = new BufferedOutputStream(new FileOutputStream(file))) {
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        throw new IOException("无法保存 PDF 校对原图");
                    }
                } finally {
                    bitmap.recycle();
                }
                state.progress(i + 1, pages.size());
            }
            state.check();
            copy = copy.withPages(added);
            store.save(copy);
            saved = true;
            return copy;
        } finally {
            if (!saved) for (ScanDocument.Page page : added) store.removeSource(copy, page);
        }
    }

    private void loadTables(String key, boolean rerun) {
        ScanDocument current = document;
        boolean handwriting = ScanRecognition.HAND_TABLE.equals(key);
        task(handwriting ? "识别手写表格" : "识别表格", true, state -> {
            org.json.JSONObject saved = store.loadReview(current, key);
            if (!rerun && saved != null) return new DraftCandidate<>(ScanRecognition.TableDraft.from(saved), false);
            boolean replacing = saved != null || store.reviewIsOutdated(current, key);
            ScanRecognition.TableDraft draft = ScanRecognition.checkedTables(
                    getAssets(), store, current, handwriting, state::progress);
            if (draft.tables.isEmpty()) {
                throw new IOException(handwriting ? "未识别到手写条目。可裁切到填写区域，确认字迹清晰后重试"
                        : "未识别到表格。可先裁切到表格区域、增强清晰度后重试");
            }
            state.check();
            if (!replacing) store.saveReview(current, key, draft.json());
            return new DraftCandidate<>(draft, replacing);
        }, candidate -> {
            ScanRecognition.TableDraft draft = candidate.draft;
            if (candidate.replaces) {
                offerDraftReplacement(current, key, summarizeTables(draft.tables), draft::json,
                        () -> openTableDraft(key, draft, rerun));
                return;
            }
            if (!rerun && !draft.checkedFor(key)) {
                new AlertDialog.Builder(this).setTitle("旧版校对稿未经过质量检查")
                        .setMessage("原有修改已保留。可对照原图继续校对，或按所选文字类型重新识别。")
                        .setNegativeButton("取消", null)
                        .setNeutralButton("继续校对旧稿", (dialog, which) -> openTableDraft(key, draft, false))
                        .setPositiveButton("重新识别", (dialog, which) -> loadTables(key, true)).show();
                return;
            }
            openTableDraft(key, draft, rerun);
        }, () -> loadTables(key, rerun));
    }

    private String summarizeTables(List<OfficeTable> tables) {
        StringBuilder summary = new StringBuilder(tables.size() + " 张表格");
        for (OfficeTable table : tables.subList(0, Math.min(tables.size(), 4))) {
            summary.append("\n第 ").append(table.page).append(" 页 · ")
                    .append(table.rows).append(" 行 × ").append(table.columns).append(" 列");
            for (OfficeTable.Cell cell : table.cells.subList(0, Math.min(table.cells.size(), 4))) {
                summary.append(" · ").append(cell.text.length() > 24 ? cell.text.substring(0, 24) + "…" : cell.text);
            }
        }
        return summary.toString();
    }

    private void openTableDraft(String key, ScanRecognition.TableDraft draft, boolean rerun) {
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
            clearBatch();
            screen = Screen.EXCEL;
            remember();
            render();
    }

    private void checkDraft(String type, Runnable recognize) {
        ScanDocument current = document;
        task("检查校对稿", false, state -> store.loadPreviousReview(current, type), previous -> {
            if (previous == null) {
                recognize.run();
                return;
            }
            new AlertDialog.Builder(this).setTitle("原图已更新")
                    .setMessage("可查看并按原页序导出之前的校对稿。重新识别后，可以先预览新稿再决定是否替换。")
                    .setNegativeButton("取消", null)
                    .setNeutralButton("查看旧稿", (dialog, which) -> {
                        boolean excel = ScanRecognition.EXCEL.equals(type) || ScanRecognition.HAND_TABLE.equals(type);
                        Runnable export = () -> prepareFile(excel ? FileFormat.XLSX : FileFormat.DOCX, current.name + " - 调整前",
                                (stream, exportState) -> {
                                    if (excel) OfficeExport.excel(ScanRecognition.TableDraft.from(previous).tables, stream);
                                    else OfficeExport.word(ScanRecognition.WordDraft.from(previous).pages, stream);
                                });
                        try {
                            String content = excel ? summarizeTables(ScanRecognition.TableDraft.from(previous).tables)
                                    : String.join("\n\n", ScanRecognition.WordDraft.from(previous).pages);
                            ScrollView scroll = new ScrollView(this);
                            TextView text = ui.text(content, 15, ScanUi.INK);
                            text.setTextIsSelectable(true);
                            text.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(8));
                            scroll.addView(text);
                            new AlertDialog.Builder(this).setTitle("原图更新前的识别稿").setView(scroll)
                                    .setNegativeButton("关闭", null)
                                    .setPositiveButton("导出旧稿", (d, w) -> export.run()).show();
                        } catch (org.json.JSONException unreadable) {
                            error("无法读取旧稿", null);
                        }
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
        String position = preferences.contains(SUBSET_SOURCE + document.id)
                ? "原第 " + originalPageNumber(document, page) + " 页 · " + (page + 1) + "/" + wordDraft.pages.size()
                : "第 " + (page + 1) + " / " + wordDraft.pages.size() + " 页";
        layout.addView(reviewBar(position,
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
        layout.addView(ui.header(handwriting ? "校对手写表格" : "校对印刷表格", this::navigateBack,
                "导出 Excel", this::exportExcel));
        layout.addView(ui.rule());
        List<OfficeTable> tables = tableDraft.tables;
        tableIndex = Math.max(0, Math.min(tableIndex, tables.size() - 1));
        OfficeTable current = tables.get(tableIndex);
        TableGridView grid = new TableGridView(this, ui, current, this::editCell);
        tableGrid = grid;
        String pagePrefix = preferences.contains(SUBSET_SOURCE + document.id) ? "原第 " : "第 ";
        String tableLabel = pagePrefix + originalPageNumber(document, current.page - 1) + " 页 · "
                + (tableIndex + 1) + "/" + tables.size();
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
        if (!tableDraft.checkedFor(tableReviewKey)) {
            TextView warning = ui.text("旧版识别稿未检查，导出前请逐项对照原图。", 13, ScanUi.ACCENT);
            warning.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8));
            layout.addView(warning);
        }
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
        if (handwriting || unreviewedCount() > 0) {
            if (uncertainCellCount() > 0) footer.addView(ui.quietButton("先核对存疑", ScanUi.ACCENT,
                    () -> nextPendingCell(null, true)));
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
            ScanRecognition.TableDraft updated = tableDraft.withTables(tables);
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
        String key = owner.id + ":" + owner.revision + "/" + page.id + ":cell:" + page.rotation + ":" + page.crop.hashCode();
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
        boolean legacy = !tableDraft.checkedFor(tableReviewKey);
        int pending = unreviewedCount();
        int uncertain = uncertainCellCount();
        boolean draft = pending > 0 || legacy;
        if (!draft && !ScanRecognition.EXCEL.equals(tableReviewKey)) {
            writeExcel();
            return;
        }
        StringBuilder message = new StringBuilder();
        if (legacy) message.append("旧版识别稿缺少质量检查记录，请对照原图。\n");
        if (pending > 0) message.append("还有 ").append(pending).append(" 格未核对，其中 ")
                .append(uncertain).append(" 格存疑。\n");
        message.append("请核对编号、金额及行列对应。");
        AlertDialog.Builder confirm = new AlertDialog.Builder(this)
                .setTitle("确认表格内容")
                .setMessage(message.toString())
                .setNegativeButton("继续校对", (dialog, which) -> {
                    if (pending > 0) nextPendingCell(null, uncertain > 0);
                })
                .setPositiveButton(draft ? "导出当前草稿" : "已核对，导出", (dialog, which) -> writeExcel());
        if (legacy) confirm.setNeutralButton("重新识别", (dialog, which) -> loadTables(tableReviewKey, true));
        confirm.show();
    }

    private void writeExcel() {
        List<OfficeTable> tables = tableDraft.tables;
        prepareFile(FileFormat.XLSX, document.name, (stream, state) -> OfficeExport.excel(tables, stream));
    }

    private void prepareFile(FileFormat format, String title, FileWriter generator) {
        String documentId = screen == Screen.PDF || document == null ? null : document.id;
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
            pendingDocumentId = documentId;
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
        Uri destination = data.getData();
        if ((data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
            try {
                getContentResolver().takePersistableUriPermission(destination, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException providerDidNotGrant) {
                // Current access can still be used for the initial save.
            }
        }
        savePendingOutput(destination);
    }

    private void savePendingOutput(Uri destination) {
        File file = pendingOutput();
        if (file == null) {
            error("临时文件已失效，请重新导出", null);
            return;
        }
        String name = pendingName;
        FileFormat format = pendingFormat;
        String documentId = pendingDocumentId;
        long bytes = file.length();
        task("保存 " + format.label, false, state -> {
            try {
                ScanExport.save(getContentResolver(), file, destination);
            } catch (IOException failure) {
                return null;
            }
            String actualName = ScanExport.displayName(getContentResolver(), destination, name);
            boolean recorded = true;
            try {
                exportHistory.record(documentId, actualName, format.name(), destination, bytes);
            } catch (IOException failure) {
                recorded = false;
            }
            return new SavedOutput(actualName, recorded);
        }, saved -> {
            if (saved == null) {
                new AlertDialog.Builder(this).setMessage("未能保存到所选位置")
                        .setNegativeButton("取消保存", (dialog, which) -> discardPendingOutput())
                        .setNeutralButton("另选位置", (dialog, which) -> launchSave())
                        .setPositiveButton("重试", (dialog, which) -> savePendingOutput(destination))
                        .setOnCancelListener(dialog -> discardPendingOutput()).show();
                return;
            }
            clearPendingOutput();
            ScanSheet sheet = new ScanSheet(this, ui, "文件已保存");
            sheet.detail(saved.name + " · " + format.label + " · " + formattedBytes(bytes)
                    + "\n" + savedLocation(destination) + (saved.recorded ? "" : "\n导出记录未写入"));
            sheet.option(R.drawable.ic_document, "打开文件", null, false, () -> openOutput(destination, format));
            sheet.option(R.drawable.ic_share, "分享文件", null, false, () -> shareOutput(destination, format));
            sheet.option(R.drawable.ic_document, "在文件中查看", null, false, () -> {
                fileFilter = FileFilter.EXPORTS;
                screen = Screen.FILES;
                render();
            });
            sheet.option(R.drawable.ic_check, "完成", null, false, () -> {});
            sheet.show();
        }, this::launchSave);
    }

    private static final class SavedOutput {
        final String name;
        final boolean recorded;

        SavedOutput(String name, boolean recorded) {
            this.name = name;
            this.recorded = recorded;
        }
    }

    private void discardPendingOutput() {
        File file = pendingOutput();
        clearPendingOutput();
        if (file != null) file.delete();
    }

    private void clearPendingOutput() {
        pendingFile = null;
        pendingDocumentId = null;
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
            state.worker = Thread.currentThread();
            try {
                state.check();
                T result = operation.run(state);
                main.post(() -> {
                    if (!complete(state)) discardResult(result);
                    else if (state.cancelled.get() && (result instanceof AutoCloseable
                            || result instanceof ScanRecognition.WordDraft
                            || result instanceof ScanRecognition.TableDraft
                            || result instanceof DraftCandidate<?>)) {
                        discardResult(result);
                        toast("已取消");
                    } else success.show(result);
                });
            } catch (CancellationException cancelled) {
                main.post(() -> {
                    if (complete(state)) {
                        toast("已取消");
                        if (preferences.contains(INCOMING_FILES)) render();
                    }
                });
            } catch (Exception failure) {
                main.post(() -> {
                    if (complete(state)) {
                        if (preferences.contains(INCOMING_FILES)) render();
                        if (state.cancelled.get()) toast("已取消");
                        else if (failure instanceof ScanRecognition.UnreliableRecognition) {
                            recognitionIssue((ScanRecognition.UnreliableRecognition) failure, retry);
                        } else error(message(title, failure), retry);
                    }
                });
            } catch (OutOfMemoryError memory) {
                images.evictAll();
                main.post(() -> {
                    if (complete(state)) error("内存空间不足，请减少单次处理页数后重试", retry);
                });
            } finally {
                state.worker = null;
            }
        });
    }

    private static void discardResult(Object result) {
        if (result instanceof AutoCloseable) {
            try { ((AutoCloseable) result).close(); }
            catch (Exception ignored) { /* Only release abandoned preview resources. */ }
        }
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
        volatile Thread worker;
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
                cancel();
                position.setText("正在取消…");
                button.setEnabled(false);
            });
        }

        void cancel() {
            cancelled.set(true);
            Thread running = worker;
            if (running != null) running.interrupt();
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

    private void recognitionIssue(ScanRecognition.UnreliableRecognition failure, Runnable retry) {
        String hint = failure.handwriting ? "字迹可能过小、模糊，或两路识别分歧较大。可裁切到文字区域后重试。"
                : "如果包含手写填写，请改用手写或混合；印刷内容可裁切、调整清晰度后重试。";
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle((screen == Screen.PDF ? "所选第 " : "第 ") + failure.page + " 页识别不可靠")
                .setMessage(hint + "\n未保存新表格，原图和已有校对稿已保留。")
                .setNegativeButton("返回原图", (d, which) -> {
                    if (screen == Screen.PDF) return;
                    if (document == null || document.pages.isEmpty()) return;
                    selectedPage = document.pages.get(Math.max(0, Math.min(failure.page - 1,
                            document.pages.size() - 1))).id;
                    if (screen == Screen.DETAIL) editFromDetail(true);
                    else if (screen == Screen.EDITOR) {
                        pendingCrop = null;
                        cropping = true;
                        render();
                    }
                });
        if (retry != null) dialog.setPositiveButton("重试", (d, which) -> retry.run());
        dialog.setNeutralButton("选择文字类型", (d, which) -> {
            if (screen == Screen.PDF) runPdfTool(PdfTool.EXCEL);
            else chooseRecognitionMode(true);
        });
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
