# 表格结构与文字配对回归

这两个脚本只使用 Python 3.10+ 标准库。仓库仅包含脚本、官方文件名和 SHA-256；第三方图片、原始注释、派生真值及测试结果都在本地 `build/` 生成，不随项目发布。

输入固定为 [PubTabNet 官方 examples](https://github.com/ibm-aur-nlp/PubTabNet/tree/8ffde9024bd331f5a61c3c549fdf30dd3c3e43a5/examples) 的完整 20 图，revision `8ffde9024bd331f5a61c3c549fdf30dd3c3e43a5`。原始 JSONL 与每张图片都有固定 SHA-256，下载和评估时均校验；真值来自原注释的 HTML、文本及 span，不来自模型预测。原注释部分明确标为 `train`，这些公开示例用于集成回归，**不能作为独立未见测试集或商用准确率证明**。样图和文章的再分发权利请另外核查。

## 准备本地数据

在项目根目录执行：

```sh
python3 tools/qa/table-structure/prepare.py --output build/table-structure/fixtures
```

脚本生成 20 张 PNG、上游原始 `truth.jsonl` 和冻结的 `frozen.json`。后者附带逻辑 `[row, column, rowspan, colspan]`，由原 HTML 计算。已存在的文件先校验再复用；校验失败时停止，不会静默接受其他版本。不要把这个输出目录加入 Git。

## Android 双路识别

安装同一源码构建的 App 与 instrumentation APK 后，运行 `TableStructureTest.externalFrozenPublicTables`。下面使用默认包名；设备须可通过 ADB 连接，且 App 已创建外部文件目录。

```sh
device_fixture_dir=/sdcard/Android/data/com.scandesk.app/files/table-structure
adb shell mkdir -p "$device_fixture_dir"
adb push build/table-structure/fixtures/. "$device_fixture_dir/"
adb shell am instrument -w \
  -e class com.scandesk.app.TableStructureTest#externalFrozenPublicTables \
  -e tableFixtureDir "$device_fixture_dir" \
  com.scandesk.app.test/androidx.test.runner.AndroidJUnitRunner
adb pull "$device_fixture_dir/android-results.json" build/table-structure/fixtures/android-results.json
```

该测试使用相同源图分别运行原尺寸印刷表路径 `nativeTables` 与 App 正常的小图工作副本路径 `tables`，记录原始 OCR、真实 Symbol 框、结构、源图 SHA、各路耗时，并导出可检查的 XLSX。小图处理有尺寸上限，源图文件不被覆盖；插值不能恢复原图缺失的信息。耗时含模型缓存影响，不是冷启动性能基准。

## 评估

```sh
python3 tools/qa/table-structure/evaluate.py \
  --fixtures build/table-structure/fixtures
```

默认读取同目录 `android-results.json`，输出 `evaluation.json`；可用 `--results`、`--output` 指定本地文件。评估要求完整 20 图、无重复结果、输入 SHA 一致，并重新验证图片和原始注释。不能用不完整的运行冒充全量回归。

结果分别报告：

- `model`：直接结构模型的完整逻辑结构一致数。
- `paths`：原尺寸与 App 全链路各自的完整结构一致数，以及该路结构正确子集上的整格文本、字符编辑距离、纯数字格统计。两个子集可能不同，不能直接换分母比较。
- `paired`：**两路结构都完全正确的相同图片、相同单元格**上的文字与纯数字配对统计，适合判断小图处理是否改善识别。

结构完全一致要求行列数及全部合并单元格一致，文字另算。文字评分只去掉源 HTML 样式及空白；保留大小写、数字、标点和正负号，不做语义纠错或数字替换。空格单元也计入整格文本统计；纯数字指标另列。人工核对仍是低分辨率和复杂结构的必要补充。
