package com.scandesk.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class ScanDocument {
    final String id;
    final String name;
    final String titleSource;
    final long modifiedAt;
    final long revision;
    final List<Page> pages;
    final Folder folder;

    ScanDocument(String id, String name, long modifiedAt, long revision, List<Page> pages) {
        this(id, name, modifiedAt, revision, pages, Folder.NONE);
    }

    ScanDocument(String id, String name, long modifiedAt, long revision, List<Page> pages, Folder folder) {
        this(id, name, modifiedAt, revision, pages, folder, "");
    }

    ScanDocument(String id, String name, long modifiedAt, long revision, List<Page> pages, Folder folder,
            String titleSource) {
        this.id = id;
        this.name = name;
        this.titleSource = titleSource;
        this.modifiedAt = modifiedAt;
        this.revision = revision;
        this.pages = Collections.unmodifiableList(new ArrayList<>(pages));
        this.folder = folder;
    }

    ScanDocument withPages(List<Page> pages) {
        return new ScanDocument(id, name, System.currentTimeMillis(), revision + 1, pages, folder, titleSource);
    }

    ScanDocument renamed(String name) {
        return new ScanDocument(id, name, System.currentTimeMillis(), revision, pages, folder);
    }

    ScanDocument filed(Folder folder) {
        return new ScanDocument(id, name, modifiedAt, revision, pages, folder, titleSource);
    }

    static final String DATE_TITLE = "date";

    ScanDocument titleFromDraft(String mode, List<String> text) {
        if ((!DATE_TITLE.equals(titleSource) && !mode.equals(titleSource)) || text.isEmpty()) return this;
        // Only page one supplies a title; edits to later pages must never rename the document.
        for (String line : text.get(0).split("\\R")) {
            String title = shortTitle(line);
            if (title.codePointCount(0, title.length()) < 2) continue;
            if (title.equals(name)) return this;
            return new ScanDocument(id, title, modifiedAt, revision, pages, folder, mode);
        }
        return this;
    }

    ScanDocument titleFromImage(String filename) {
        if (!DATE_TITLE.equals(titleSource) || filename == null) return this;
        int suffix = filename.lastIndexOf('.');
        String title = shortTitle(suffix > 0 ? filename.substring(0, suffix) : filename);
        if (title.isEmpty() || title.matches("(?i)(?:(?:img|image|pxl|dscn?|photo|screenshot|mmexport|wx_camera)[_ -]*)?[0-9][0-9a-f_ .:()\\-]*")
                || title.matches("(?i)[0-9a-f]{32}|[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) return this;
        return renamed(title);
    }

    private static String shortTitle(String value) {
        String name = value.replaceAll("[\\p{Cntrl}/\\\\:*?\\x22<>|]+", " ").replaceAll("[\\s\\p{Z}]+", " ").trim();
        int length = name.codePointCount(0, name.length());
        return length <= 40 ? name : name.substring(0, name.offsetByCodePoints(0, 40)).trim();
    }

    enum Folder {
        NONE("未分类"), IDS("证件夹"), ALBUM("我的画册"), MEETINGS("会议资料"),
        QUESTIONS("我的题目"), MISTAKES("错题本");

        final String label;
        Folder(String label) { this.label = label; }
    }

    enum Filter {
        AUTO("自动增强", "提亮阴影，增强文字，保留彩色标记"),
        ORIGINAL("原色", "保留校正后的原始色彩"),
        COLOR("彩色增强", "减淡阴影，提高色彩对比"),
        SCREEN("拍屏去彩纹", "减淡翻拍屏幕时出现的细密彩条"),
        GRAY("灰度", "弱化底色，保留灰度细节"),
        MONO("黑白", "提高文字和线条的对比度");
        final String label;
        final String description;

        Filter(String label, String description) {
            this.label = label;
            this.description = description;
        }
    }

    static final class Page {
        final String id;
        final int rotation;
        final CropQuad crop;
        final Filter filter;
        final Page beforeFlatten;

        Page(String id, int rotation, CropQuad crop, Filter filter) {
            this(id, rotation, crop, filter, null);
        }

        Page(String id, int rotation, CropQuad crop, Filter filter, Page beforeFlatten) {
            this.id = id;
            this.rotation = rotation;
            this.crop = crop;
            this.filter = filter;
            if (beforeFlatten != null && (!id.equals(beforeFlatten.id) || beforeFlatten.beforeFlatten != null)) {
                throw new IllegalArgumentException("书页原稿状态无效");
            }
            this.beforeFlatten = beforeFlatten;
        }

        Page rotate() {
            return new Page(id, (rotation + 1) % 4, crop.clockwise(), filter, beforeFlatten);
        }

        Page crop(CropQuad crop) {
            return new Page(id, rotation, crop, filter, beforeFlatten);
        }

        Page filter(Filter filter) {
            return new Page(id, rotation, crop, filter, beforeFlatten);
        }

        boolean isFlattened() { return beforeFlatten != null; }

        Page flattened() {
            return new Page(id, 0, CropQuad.FULL, filter, isFlattened() ? beforeFlatten : this);
        }

        Page restoreFlattening() { return isFlattened() ? beforeFlatten : this; }
    }
}
