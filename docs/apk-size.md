# rc.13 体积核验

本轮按用户选择保留书页展平与高精度手写识别，采用 R8 代码和资源裁剪、DEX 无损 ZIP 压缩。五个 ONNX 模型、字典、ML Kit 模型、PDF 字体等资产与全部原生库保持原始字节。ART 编译配置随优化后的 DEX 重新生成；图像处理参数与业务代码保持。

| 指标 | 对照 | rc.13 |
| --- | ---: | ---: |
| ARM64 APK 字节 | 231,661,417 | 215,873,255 |
| APK 大小，十进制 MB | 231.7 | 215.9 |
| DEX 原始代码字节 | 20,823,228 | 13,168,896 |
| APK 内 DEX 压缩后字节 | 20,823,228 | 5,100,421 |
| ONNX 模型字节 | 145,327,371 | 145,327,371 |
| 原生库字节 | 60,936,176 | 60,936,176 |
| 模拟器系统报告的 AppBytes | 232,394,752 | 229,560,320 |

下载包减少 **15,788,162 字节，约 15.8 MB（6.815%）**。安装后的程序占用在该 Android 16 ARM64 模拟器减少 **2,834,432 字节，约 2.8 MB**，不能把下载节省直接算作手机空间节省。安装对照使用同一源码、同版本号的关闭 R8/DEX 压缩构建；通过当前应用 UID 调用 `StorageStatsManager.queryStatsForPackage`，读取 `StorageStats.getAppBytes()`，用户数据和缓存另列，未纳入这项比较。其他手机的 ART 产物和占用可能不同。[Android StorageStats](https://developer.android.com/reference/android/app/usage/StorageStats#getAppBytes())

原生库继续在 APK 中保持未压缩、页对齐的形式，系统可直接映射。压缩原生库会改变安装时的解压策略，不能只凭下载文件变小就判定安装占用更少。[Android 原生库安装说明](https://developer.android.com/guide/topics/manifest/application-element#extractNativeLibs)

## 核查与回归

- 精简 APK 对 164 项原生库及普通资产逐一计算 SHA-256，与 rc.12 全部一致。两份 `assets/dexopt/` 编译配置因 R8 重新生成，单独记录，未当作识别模型。
- Android 16 ARM64、飞行模式、1.25 倍字体下，精简后的 release APK 完整执行 116 项仪器回归，116 成功、0 跳过、0 失败。包含外部书页、手写与表格探针，测试数据不随源码或 APK 分发。
- 同一批 20 张公开表格的完整输出与 rc.12 相同，仍是 17/20 全结构一致；这证明本轮未改变这些样例的结果，不代表达到未见文档全自动无误。
- 实际启动主界面、相机预览并开关引导，检查文字、图标和导航。模型精度、手写候选、Excel/Word/PDF 等功能保留。
- 保留 JNI、反射及独立验收 APK 使用的公共 API，避免裁剪造成类名或方法链接失败。R8 与 AndroidTest 构建均验证；源码默认启用 release 裁剪，可用 `-PscanMinify=false` 做代码裁剪对照。[Android R8](https://developer.android.com/build/shrink-code) · [ONNX Runtime 保留规则](https://onnxruntime.ai/docs/build/android.html) · [DEX 打包设置](https://developer.android.com/reference/tools/gradle-api/8.13/com/android/build/api/dsl/DexPackaging)

可下载两个版本的 APK 后复核包内数据：

```sh
python3 tools/qa/apk_size.py --baseline old.apk --candidate new.apk --output build/apk-comparison.json
```

脚本核验包内普通资产和原生库字节，单列 ART 编译配置变化。它不替代功能回归或安装后占用测量。本版继续使用开发验收签名，真机商用验收边界见[验证记录](validation.md)。

## rc.15 界面与流程调整

rc.15 ARM64 APK 为 215,922,407 字节，较 rc.14 增加 49,152 字节（48 KiB，约 0.023%）。164 项普通资产与原生库逐项 SHA-256 不变；未增加识别或展平模型。新增代码用于工作模式、任务恢复、文件导航、校对与导出记录，以及相应的界面改进。
