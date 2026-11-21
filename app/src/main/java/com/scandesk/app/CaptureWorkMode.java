package com.scandesk.app;

enum CaptureWorkMode {
    FILE("file", "扫描文件", "将纸张四角留在取景区内", Integer.MAX_VALUE),
    ID("id", "扫描证件", "", 2),
    TEXT("text", "提取文字", "让字迹清晰，保持阅读方向", Integer.MAX_VALUE),
    TABLE("table", "提取表格", "拍完整行列，保留表格边缘", Integer.MAX_VALUE),
    QR("qr", "扫码", "", 1);

    final String value;
    final String label;
    final String hint;
    final int maximumPages;

    CaptureWorkMode(String value, String label, String hint, int maximumPages) {
        this.value = value;
        this.label = label;
        this.hint = hint;
        this.maximumPages = maximumPages;
    }

    static CaptureWorkMode fromValue(String value) {
        for (CaptureWorkMode mode : values()) {
            if (mode.value.equals(value)) return mode;
        }
        return FILE;
    }

    boolean usesPaperGuidance() {
        return this != ID && this != QR;
    }
}
