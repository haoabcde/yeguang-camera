# 夸克扫描王的扫描流程与页光相机的实现

2026 年 11 月 21 日核查。页光能在离线 Android 上从手拍照片生成可编辑的 Word 和 Excel。对照夸克公开能力，本轮在 rc.11 的基础上补充**拍摄前发现问题、弯曲书页展平、复杂表格的行列还原**，分别处理离开现场才发现拍糊、书脊附近字行弯曲、文字读对却进了错误单元格的问题。

## 夸克公开了哪些原理

夸克的算法公示明确将图片文字检测、识别和格式转换用于「提取文字」「提取表格」。自动或手动选区后，系统提取图像文字特征，再结合语言模型给候选文本打分，输出得分最高的结果。同一公示还说明，智能擦除涉及裁切矫正、目标区域分割和背景恢复。后者解释了擦除的一种流程，文档阴影的具体算法仍未披露。[夸克算法原理及数据处理情况说明](https://terms.alicdn.com/legal-agreement/terms/c_end_product_protocol/20240423150356152/20240423150356152.html)

公开帮助确认了连续扫描、手调裁切、平面透视校正、弯曲矫正、减少阴影和保留印章等用户可见能力。开放平台将通用 OCR、手写、表格、去阴影、去屏纹和图转 Excel 设为不同服务。由此可作的产品推断是，预先选择文档、屏幕或手写表格，能让用户少调参数并直接获得所需格式。公开入口不足以确定每个场景是否运行独立模型。具体检测网络、去阴影参数、语言模型名称和训练数据明细，以及当前安卓客户端每一功能的端云分工，均无法从所查资料确认。本次也没有在同一手机、同一组照片上运行两个 App 比较效果。[扫描帮助](https://www.quark.cn/documents/help/file-scan-upload) · [开放平台场景](https://scan.quark.cn/business/docs-v2/guide/tutorial-on-accessing-cli)

## 从镜头到文件的七个环节

| 环节 | 夸克公开能力 | 页光 rc.11 的实现与待验证问题 |
| --- | --- | --- |
| 采集引导 | 支持拍摄、相册及连续扫书，官方建议避开反光和遮挡。[扫描帮助](https://www.quark.cn/documents/help/file-scan-upload) | CameraX 支持点按对焦测光、多页连拍，拍后可针对严重暗图或模糊重拍当前页。当前预览绑定预览和拍照两项，尚无逐帧纸边、反光或稳定度提示。模拟相机已走通，真实镜头的误提示率待测。[相机](../app/src/main/java/com/scandesk/app/CaptureActivity.java) · [拍后画质判定](../app/src/main/java/com/scandesk/app/CaptureQuality.java) |
| 纸边、透视与书页 | 公开说明明确区分纸张边界、斜拍矫正及书页折痕的弯曲矫正，保留手动调整。[智能裁剪](https://www.quark.cn/documents/help/file-scan-upload) | OpenCV 查轮廓并用边界内外亮度差给候选评分，手选四角后做平面投影。对开拆页能左右分开，书脊处一条弯曲的基线仍需局部变形。已有回归覆盖误把表格外框当纸边而切掉标题的故障。[边界与投影](../app/src/main/java/com/scandesk/app/ScanImages.java) · [边界回归](../app/src/androidTest/java/com/scandesk/app/DocumentBorderTest.java) |
| 去阴影与清晰度 | 对纸张强调阴影减淡并保留文字、签章和格线，拍屏另有去摩尔纹。[扫描帮助](https://www.quark.cn/documents/help/file-scan-upload) | 页光分别估计大范围纸面明暗和硬阴影边缘，再按墨迹与纸面的亮度比例增强。拍屏单独处理色纹。旧版至新版的合成单据上，阴影边缘与同侧空白纸面的亮度差由 77.9 降至 0.8（满值 255），淡线与纸面的对比由 41 变为 38。它提示了去影与保留淡字的取舍。仍需要有、无阴影的同页实拍对照，尤其是红章、浅铅笔和纸张本色。[增强实现](../app/src/main/java/com/scandesk/app/ScanImages.java) · [阴影细节回归](../app/src/androidTest/java/com/scandesk/app/ShadowEnhancementTest.java) |
| 手写中文 OCR | 官方支持照片中的手写笔记、印刷文字、公式等，并说明尽量保留阅读顺序。[文字提取](https://www.quark.cn/documents/help/scan-text-extraction) | 手写路径用 PaddleOCR 行检测、透视裁条和字符解码。手写转表格另外按需加载约 84.5 MB 的 server 识别权重，与 mobile 和全页框交叉核对，两套模型均在手机运行。八位书写者的固定三行摘录有 28/450 个字符编辑错误，另三份现场摘录有 14/51 个，长度、写法、拍摄条件均不同。应按陌生书写者和整页实拍复测。[手写识别](../app/src/main/java/com/scandesk/app/HandwritingOcr.java) · [模型出处](../app/src/main/assets/licenses/paddleocr-handwriting.txt) |
| 行列恢复、可编辑 Excel | 官方表格介绍描述了行列、合并单元格及可编辑工作簿。开发 API 另支持多页合并输出。[表格说明](https://scan.quark.cn/business/news-detail/26071003.html) · [Excel 接口](https://scan.quark.cn/business/docs-v2?referenceId=28&tab=api) | 印刷表格结合格线和文字位置重建行列。无格线手写稿按原图位置排版，可对照原图调整分列。实测一张手写清单中 70 条记录全部唯一定位，69 条清楚记录有 66 条全文正确。列归属正确和转录正确要分开计数。混排报表中的嵌套表头、多段不同列数及续表尚缺广泛证据。[印刷表格](../app/src/main/java/com/scandesk/app/TableVision.java) · [手写布局](../app/src/main/java/com/scandesk/app/HandwrittenTableLayout.java) · [工作簿导出](../app/src/main/java/com/scandesk/app/OfficeExport.java) |
| 校对与更正 | 产品官网展示按字或段选择复制、转 Office 后编辑。所查资料未给出其置信度阈值或逐格复核规则。[产品官网](https://scan.quark.cn/) | 页光保留文字、原图框、备选和人工核对状态。上述清单的 3 处明确错读都提示存疑，11 条清楚但存疑的记录里有 8 条首选已正确。其中 1 处错读连备选也错。应同时衡量漏报与打扰次数。rc.11 允许先看存疑项，重新分列保留更正，真实导出回读的 120 格内容及类型与调整前一致。[表格数据](../app/src/main/java/com/scandesk/app/OfficeTable.java) · [校对流程](../app/src/main/java/com/scandesk/app/MainActivity.java) |
| 导出、找回与离线 | 提供图转 PDF、Word、Excel，以及集中管理、搜索和跨端同步。2024 年 6 月版独立 App 隐私政策专门说明离线扫描本地处理，以及一般保存和转换保存的云端路径。[格式转换](https://www.quark.cn/documents/help/format-conversion) · [搜索与同步](https://www.quark.cn/documents/help/scan-view-search) · [独立 App 隐私政策](https://terms.alicdn.com/legal-agreement/terms/c_end_product_protocol/20230704104443733/20230704104443733.html) | 页光从扫描页逐页写图片 PDF。Office 输出可以编辑，本机可按已校对文字找回。扫描件库和库外 PDF 文件各有自己的管理范围。主应用清单移除联网权限，Android 系统选择器允许用户指定文件提供方。换机恢复编辑草稿仍待开发。搜索已有扫描件与在外部阅读器搜索图片 PDF 的文字是两个能力。[PDF 输出](../app/src/main/java/com/scandesk/app/ScanExport.java) · [检索](../app/src/main/java/com/scandesk/app/DocumentSearch.java) · [应用权限](../app/src/main/AndroidManifest.xml) · [Android 文件选择器](https://developer.android.com/training/data-storage/shared/documents-files) |

## rc.12 的实现与后续验收

拍前引导沿用 CameraX 官方 ImageAnalysis 与坐标转换，并参考 [android/camera-samples](https://github.com/android/camera-samples)。分析与拍照共享 ViewPort，单线程只处理最新帧，间隔至少 350 ms，提示暗光、局部过亮、纸边不全、距离、模糊与移动；稳定后仍由用户按快门。不支持三路 UseCase 的相机回退至普通拍摄。亮斑判断是提示，不能保证发现每一处反光遮字；真机上的误提示、焦点与性能仍需测量。[CameraX 坐标说明](https://developer.android.com/media/camera/camerax/transform-output) · [引导实现](../app/src/main/java/com/scandesk/app/CaptureGuidance.java)

曲面展平使用官方 UVDoc ONNX 的网格子图，保留原权重，将大图重采样改为 Android 分条执行，避免全分辨率浮点图像和位移图同时驻留。模型接收已裁切、转正的单页原色图，限制长边 3000 像素。输出先作几何和内容保护检查，复用现有文字检测器检查短页眉、页码和页脚，检测失败也会停止应用；再显示可缩放的前后对照，由用户确认。自动纸边也按实际轮廓向外包络，无法安全保全弯曲页边时保留完整原图。原始照片、原来的裁切、方向和滤镜设置保留，恢复时切回原稿；展平后重新识别，旧校对稿标为过期，避免把旧来源框套到变形后的图上。子图修改、摘要与重建步骤见[UVDoc 声明](../app/src/main/assets/licenses/uvdoc.txt)和[重建脚本](../tools/prepare_uvdoc.py)。

复杂印刷表格使用原 OCR 字符串与 SLANet_plus 的单元格、跨行跨列结构匹配，保留可靠的格线结果，并为候选区域补充结构识别。只有存在重复列锚点与贯通空白等明确证据时才纠正模型的过度合列；OCR 跨列片段的拆分也核对实际字符框与原文索引，不改猜测出的数字。长边不足 1200 像素的小图在识别副本中有界放大，最多 4 倍、长边 1600，原图文件保持原样。插值帮助识别器采样，不能恢复拍摄时已缺失的文字细节。附近正文和旁注、最终几何回退中的单格标题及尾行，保留补充工作表。自由手写名单继续使用原有几何分列及人工校对，不把印刷表结构模型强套上去。结构正确、OCR 文本正确及 XLSX 可编辑是分别验证的三个指标。[结构模型适配](../app/src/main/java/com/scandesk/app/TableStructureOcr.java) · [固定来源与许可](../app/src/main/assets/licenses/slanet-plus.txt) · [ML Kit 输入建议](https://developers.google.com/ml-kit/vision/text-recognition/v2/android#input-image-guidelines)

以下保留试验设计，用于继续补充多设备、独立文档与同页成对拍摄证据：

1. **快门前提示纸边、晃动和遮挡文字的高光。** 在现有 CameraX 旁加低分辨率 YUV 帧分析，只保留最新帧，复用已经过回归的纸边候选。按旋转和预览裁切换算四角，连续几帧稳定再显示引导。保留手动快门。用白纸、覆膜页、白底表格和移动相机分别测边框抖动、误提示率、被漏掉的反光遮字和预览卡顿。在至少两种品牌、不同性能档位的真机上记录首次成片可用率与重拍次数。可沿用工程内的 CameraX 和 OpenCV 4.11，两者源码均采用 Apache-2.0，此路径无需新增模型权重。[CameraX 帧分析](https://developer.android.com/media/camera/camerax/analyze) · [预览坐标换算](https://developer.android.com/media/camera/camerax/transform-output) · [AndroidX 许可](https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt) · [OpenCV 许可](https://opencv.org/license/)
2. **把书脊旁的字行真正拉平。** 在「扫书」中分左右页，比较现有四角结果与 UVDoc 的网格位移。预测小网格后映回原分辨率，由 OpenCV 重采样，并保留原稿和每一条 OCR 来源框的坐标映射。用同页平铺、轻卷、深书脊三种姿态测试，按文字基线最大偏移与行高之比、丢字行数、展平前后字符错误率以及峰值内存判定。平整原稿是回归对照。原作者代码为 MIT。Paddle 发布的 ONNX 权重标注 Apache-2.0，文件为 31,684,150 字节，模型 SHA-256 为 `54cab30dc2cf347b4f3d6971c833b1c3d84f5ef17280f1f6d15796bff20e63d6`。此处核对的是发布元数据，真机算子、延时和精度尚待实测。[作者论文与代码](https://github.com/tanguymagne/UVDoc) · [固定版本权重](https://huggingface.co/PaddlePaddle/UVDoc_onnx/tree/3bcf535371727d11e783101f79a504c68848aae3) · [OpenCV 坐标映射](https://docs.opencv.org/4.x/da/d54/group__imgproc__transform.html)
3. **为复杂表格补充结构预测。** 现有 OCR 继续负责每段原文，另外试用 SLANet_plus 推断单元格和跨行跨列关系，再将文字框对齐到格。规则识别与结构模型在同一批两层表头、无框表、空值、同页两表和自由手写清单上比较。分别统计行列归属、合并区域完全一致率、数字全文准确率、编号的文本类型及人工校对耗时。模型卡为 Apache-2.0，官方 ONNX 文件为 7,782,138 字节，SHA-256 为 `7790c0c13ce064782c9d22ebeb16b4da8216f83d3ba576da962c106ef58386da`。上游结构模型针对表格训练，自由排列的手写名单要作为单独场景验证。[Paddle 表格流程](https://www.paddleocr.ai/main/en/version3.x/pipeline_usage/table_recognition_v2.html) · [模型卡及许可](https://huggingface.co/PaddlePaddle/SLANet_plus_onnx) · [固定版本权重](https://huggingface.co/PaddlePaddle/SLANet_plus_onnx/tree/7dbe640e127602bf506815e822c09758de73c482)

阴影增强应与这三项并行补数据。推荐同一纸面、固定机位，分别拍无影、软影、手机硬影，固定印章、签字、铅笔和细表格线的测量区域。对同一原件比较原色、当前增强和候选算法的暗侧残影、淡笔画对比、章色偏移、OCR 字符错误率及金额整项准确率。有高光遮住文字时给出移灯或换角度的提示。公开论文可为估计纸面照明、采集同页成对样本提供依据。新增权重前先用自采有授权的对照排查现有算法的实际失败场景。[基于局部纸色的阴影估计](https://civc.ucsb.edu/graphics/Papers/ACCV2016_DocShadow/) · [高分辨率实拍阴影研究](https://openaccess.thecvf.com/content/ICCV2023/html/Li_High-Resolution_Document_Shadow_Removal_via_A_Large-Scale_Real-World_Dataset_and_ICCV_2023_paper.html)

对于中文错字，值得单独试在模糊或模型分歧的行上回取原照片像素，再识别一次，并比较备选的收益。语言模型给普通句子打分可能利用上下文，面对人名、编号和金额时却容易偏向常见写法。应把影响单号、负号、小数点或姓氏的修改作为候选，保留可放大的字形。验收同时统计正确候选覆盖率、错误候选采纳率和每页人工核对用时。这里是页光的试验设计，夸克公开资料只确认其采用语言模型解码评分。[夸克算法公示](https://terms.alicdn.com/legal-agreement/terms/c_end_product_protocol/20240423150356152/20240423150356152.html)

## 技术选型与商用验收边界

PaddleOCR 官方 Android 源码及权重可分别核查版本与许可，适合复用到当前 ONNX Runtime。Google ML Kit 中文 OCR 可选随包模型，受 Google 的 SDK 条款约束。Google 的整套文档扫描界面由 Play 服务动态下载，首次断网或无 Play 服务的手机需另外验证。Digital Ink 接受笔画轨迹，照片手写识别应继续使用图像检测与识别路径。夸克开放平台提供 CLI、Agent Skill 等服务接入方式，需要 API Key，选择本地文件后仍要调用服务。本项目调研使用公开资料，未调用该服务处理样张。[PaddleOCR 上游](https://github.com/PaddlePaddle/PaddleOCR) · [ML Kit 中文文字](https://developers.google.com/ml-kit/vision/text-recognition/v2/android) · [ML Kit 条款](https://developers.google.com/ml-kit/terms) · [Google 文档扫描器](https://developers.google.com/ml-kit/vision/doc-scanner/android) · [数字墨迹输入](https://developers.google.com/ml-kit/vision/digital-ink-recognition) · [夸克开放平台接入](https://github.com/alibaba-quark-scanking/alibaba-quark-scanking)

rc.11 在 Android 16 ARM64 模拟器飞行模式下通过 81 项仪器测试，沿用 rc.10 的 OCR 权重。那张手写清单在 rc.10 的识别和构表耗时约 10.8 秒。商用评估还需在目标真机首次离线安装后走完拍摄、校对、退出恢复和系统另存，用独立阅读器逐页查看 PDF、在表格软件重开 XLSX。按书写者、设备和整份文档独立留样，保留未经人工修改的初稿，另测校对成品与耗时。若要比较夸克，用双方均可使用的公开或获授权纸件，在同机同光照分别测拍摄体验，再导入相同照片比较算法出件，记录客户端版本、网络、模式及会员状态。[页光验证方法](validation.md)
