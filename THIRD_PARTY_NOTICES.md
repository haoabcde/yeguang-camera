# 第三方软件与素材

页光相机的原创代码与文档采用 [Apache License 2.0](LICENSE)。下列组件、模型和资料保留各自的著作权及许可。应用分发的第三方许可证和声明保存在 [assets/licenses](app/src/main/assets/licenses/)。

| 组件 | 项目中的用途与条款 |
| --- | --- |
| PaddleOCR、PP-OCRv5 | 行检测、文字识别和字符表。PaddlePaddle 发布的三个 ONNX 模型使用 Apache-2.0。移动版识别字典取自同版配置。其余两份模型的固定版本、摘要，以及 Android 文字行组件所取的上游提交均记录在案。[移动识别模型](https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_rec_onnx/tree/ed152b8b495f84de93cda5709d768548a9127622) · [检测、大型识别模型与 SDK 来源](app/src/main/assets/licenses/paddleocr-handwriting.txt) · [许可](app/src/main/assets/licenses/paddleocr.txt) |
| UVDoc | 单页曲面展平。PaddlePaddle 发布的 ONNX 权重为 Apache-2.0，Android 使用保留原权重的网格子图和分条重采样；原始 UVDoc 项目为 MIT。固定版本、修改方式、摘要与重建方法见[模型声明](app/src/main/assets/licenses/uvdoc.txt)。 |
| SLANet_plus、PaddleX | 表格结构、跨行跨列与单元格位置。官方 ONNX 权重、字典及适配的预处理、解码流程为 Apache-2.0；匹配保留原始 OCR 字符串。[版本、修改与许可](app/src/main/assets/licenses/slanet-plus.txt) |
| ONNX Runtime Android | Microsoft 的模型推理运行时。主体使用 MIT，随附组件各自适用上游许可。[MIT 许可](app/src/main/assets/licenses/onnxruntime.txt) · [上游第三方声明](app/src/main/assets/licenses/onnxruntime-third-party.txt) |
| OpenCV、LLVM libc++ | 图像处理使用 OpenCV 4.11。构建另从 4.12 的 Android 包取得 libc++ 运行库。OpenCV 使用 Apache-2.0，libc++ 使用含 LLVM 例外的许可证。[OpenCV](https://opencv.org/license/) · [LLVM 许可及例外](app/src/main/assets/licenses/llvm-runtime.txt) |
| AndroidX、CameraX、AndroidX Test | Android 界面、拍摄和仪器测试依赖，由 Gradle 解析。各制品保留 Android Open Source Project 及贡献者的声明。[AndroidX](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/LICENSE.txt) |
| Google ML Kit Text Recognition | 中文印刷文字识别依赖由 Gradle 从 Google Maven 获取。Google 提供 SDK 和相关软件，其使用与分发适用 ML Kit 和 Google APIs 条款。[ML Kit 条款与隐私说明](https://developers.google.com/ml-kit/terms) · [Google APIs 条款](https://developers.google.com/terms) |
| PdfBox-Android | 本地 PDF 操作。Tom Roush 的 Android 移植和 Apache PDFBox 使用 Apache-2.0，另含字体数据等第三方声明。[许可](app/src/main/assets/licenses/pdfbox-android.txt) · [NOTICE](app/src/main/assets/licenses/pdfbox-android-notice.txt) |
| Bouncy Castle | PDF 依赖使用 Bouncy Castle Java 密码组件，采用 MIT 许可。[上游许可副本](app/src/main/assets/licenses/bouncycastle.html) |
| ZXing Core | 二维码读取使用 Apache-2.0，随附组件声明见[许可文件](app/src/main/assets/licenses/zxing.txt)。 |
| Material Design Icons | Android 界面使用的相关矢量图标遵循 Apache-2.0。[Google 项目](https://github.com/google/material-design-icons) · [许可副本](app/src/main/assets/licenses/material-design-icons.txt) |
| Gradle Wrapper | 固定为 Gradle 8.13。启动脚本和 Wrapper 使用 Apache-2.0。[上游许可](https://github.com/gradle/gradle/blob/v8.13.0/LICENSE) · [官方校验值](https://gradle.org/release-checksums/) |

公开仪器测试中的两张采购清单由本项目脚本绘制。印刷 OCR 现场测试使用 Wikimedia Commons 的外部图片，每幅的文件页、作者及许可列在[样张索引](tools/qa/field-ocr/samples.json)。这些图片需自行从来源取得。
