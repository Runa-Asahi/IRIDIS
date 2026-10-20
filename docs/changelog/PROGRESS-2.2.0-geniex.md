# 2.2.0 · GenieX 引擎（full 渠道）进度

分支 `feat/geniex`。把高通 GenieX（`com.qualcomm.qti:geniex-android:0.8.0`）接入为第四个推理引擎，只在 full 渠道。

## 做法：按需下载的外挂运行库

- APK 只带 GenieX 的 Kotlin/Java 层（classes.jar ≈ 145 KB，R8 后更小）；AAR 里的 51 个 .so 全部在 `packaging.jniLibs.excludes` 里排除（`libnpu_jni`、`libgeniex*`、`libllama`、`libllama-common`、`libmtmd`、`libggml*`、`libomp`、`libQnn*`、`libhta_hexagon_runtime_*`、`libPlatformValidatorShared`、`libcalculator`、`libCalculator_skel`）。与自带库不重名（`libiris_llama.so` / `libiris_mnn.so` / `liblitertlm_jni.so` / sherpa），不需要 pickFirst。
- 设置 › 模型与后端 › GenieX：先弹高通许可说明（Kotlin 层 BSD-3；原生运行库为高通专有，Qualcomm Terms of Use），同意后从 Maven Central 下载 `geniex-android-0.8.0.aar`（失败换 maven.aliyun.com 镜像，断点续传），校验整包大小 + SHA-256，再只解出本机需要的 arm64 库到 `filesDir/geniex/rt-0.8.0-v{arch}/`，每个文件校验大小 + SHA-256（固定在代码里），写标记文件；之后每次查询校验存在 + 大小，不对显示「需要修复」。可删除 / 修复。
- 骁龙 8 Elite（SM8750，Hexagon V79）解出 20 个文件 ≈ 50 MB（下载 82 MB，下载包装完即删）：
  - 公共 + llama_cpp：libggml-base/cpu/opencl/hexagon、libggml、libllama、libllama-common、libmtmd、libgeniex、libnpu_jni、libggml-htp-v79（DSP 端），插件 `llama_cpp/libgeniex_plugin.so`
  - qairt：libgeniex-proc、libgeniex-proc-vision、libgeniex_core、libgeniex_vlm、插件 `qairt/libgeniex_plugin.so`、libQnnSystem、libQnnHtp、libQnnHtpNetRunExtensions、libQnnHtpV79Stub、libQnnHtpV79Skel（DSP 端）
  - 不下载：V81/V75/V73 的 HTP 库、HTA、SNPE、PlatformValidator、calculator 示例、Profiling/Optrace/Chrometrace/Json 读取器、QnnSaver、QnnIr、QnnModelDlc、NetRunDirect、libomp（无人链接）、libQnnHtpV79.so，以及 libQnnHtpPrepare.so（87 MB，在线编图用；GGUF 不走 QNN，QAIRT 包是预编译 context binary，不需要。若真机某个包在加载时报缺 libQnnHtpPrepare，再把它加入清单）。
  - 8 Gen 2/8 Gen 3（V73/V75）只装 GGUF 部分（GenieX 不带这两代的 QNN skel）；8 Elite Gen 5 装 V81 版。
- 加载方式（GenieXRuntime.ensureLoaded）：GenieX 的 `GenieXSdk` 类写死 `System.loadLibrary("npu_jni")` 并只在 nativeLibraryDir 找插件，所以完全不碰它。改为：
  1. `Os.setenv`：`GENIEX_PLUGIN_PATH=<dir>`（geniex_init 扫描 `<dir>/<id>/libgeniex_plugin.so` 注册插件；llama.cpp 插件也用它设置 `ADSP_LIBRARY_PATH`，FastRPC 从这里找 `libggml-htp-v79.so`）、`GENIEX_QAIRT_LIB=<dir>`（qairt 插件官方的运行库覆盖：按绝对路径加载 libQnnHtp/System/NetRunExtensions，并把 `ADSP_LIBRARY_PATH` 指到这里找 QNN skel）、`ADSP_LIBRARY_PATH=<dir>;原值`；
  2. 按依赖顺序 `System.load` 主机端库（每个 NEEDED 都先于使用者加载，单测按 readelf 结果校验）；
  3. 最后 `System.load(libnpu_jni.so)`，其 JNI_OnLoad 调 geniex_init 完成插件注册；`com.geniex.sdk.jni.Llm/Vlm` 的 native 方法经 app ClassLoader 解析。
  QNN 主机库只在第一次加载 QAIRT 包前加载（ensureQnnLoaded）。
  （`setQairtRuntimePath` 在 Android 上不可用：JNI_OnLoad 已调用 geniex_init 并锁定该路径，所以用环境变量 `GENIEX_QAIRT_LIB`。）

## 引擎

- `GenieXEngine`（full）：
  - GGUF：`llama_cpp` 运行时，后端 NPU（默认，自动）/ GPU（OpenCL）/ CPU，失败按 NPU → GPU → CPU 回退（沿用 EngineManager 链）。同目录有匹配的 `mmproj-*.gguf` 时走 VlmWrapper（读图）；没有则发图时明确报错。
  - AI Hub QAIRT 包文件夹（新格式 `ModelFormat.QAIRT`，识别：`*.bin` + `genie_config.json`/`metadata.json`）：只能 NPU；`n_ctx`/`n_gpu_layers` 传 0（包内固定）；上下文长度从 genie_config.json 读；文件夹有 `vision_encoder.bin` 时走 VLM。
  - 流式：`generateStreamFlow`；停止：`stopStream`（不等待正在运行的原生调用）；系统提示 = 第一条 system 消息，全程走 `applyChatTemplate`（`enable_thinking` 跟随深度思考）；多轮：llama_cpp 自带前缀 KV 复用；QAIRT 的 KV 在原生侧累积，历史不是「上次对话 + 新消息」（重新生成/编辑/换系统提示）时先 `reset()`（GenieXTurns，单测覆盖）。
  - 采样：GenieX 的 0 = 用默认值，所以温度 0 → top-k 1，随机种子 -1 → 0；上下文平移 → `GenerationConfig.slidingWindow`（仅 GGUF）。
  - 错误：中文说明 + 原始异常（类名、消息、cause）；常见 SDK 错误文本附提示（文件缺失、参数不支持、插件未加载…）。JNI create 失败但没抛异常（句柄 0）也会报错。
  - 非高通芯片 / 不支持的骁龙：设置里显示原因，模型后端选项全部灰掉并写明「GenieX 只支持高通骁龙芯片；本机是 …」。
  - 本进程已加载 LiteRT NPU 的 QNN 2.50 时拒绝加载 QAIRT 包并提示重启（两套 QNN 版本不同，同进程混用会出错）。
- 选择引擎：新全局设置 `ggufEngine = "llama"`（默认，现有行为不变）；模型设置里每个 GGUF 可选「跟随默认 / llama.cpp / GenieX」（`ModelOverrides.engine`，默认 null）。
- 模型列表 / 选择器：GenieX 运行的模型显示「GenieX · NPU/GPU/CPU」（加载后显示实际后端，如「GenieX · NPU · Hexagon · llama.cpp」）。

## 改动文件

- 新增：`app/src/main/java/com/iris/localai/engine/geniex/GenieX.kt`（纯逻辑：库清单 + SHA-256、arch 映射、安装标记校验、QAIRT 包识别、mmproj 匹配、多轮判定、采样映射、错误文本、后端选项）、`app/src/full/java/com/iris/localai/engine/geniex/GenieXRuntime.kt`、`GenieXEngine.kt`、`app/src/main/java/com/iris/localai/ui/settings/GenieXSettings.kt`、`app/src/main/assets/licenses/bsd-3-geniex.txt`、`app/src/test/java/com/iris/localai/v220/V220GenieXTest.kt`
- 修改：`app/build.gradle.kts`（fullImplementation + jniLibs 排除）、`gradle/libs.versions.toml`、`app/proguard-rules.pro`（keep com.geniex.sdk.**、kotlin.Pair）、`data/Models.kt`（ModelFormat.QAIRT、AppSettings.ggufEngine）、`data/ModelConfig.kt`（ModelOverrides.engine、EffectiveModelConfig.engine）、`data/SettingsRepository.kt`、`data/ModelRepository.kt`（QAIRT 文件夹识别与导入）、`engine/Backend.kt`、`engine/EngineManager.kt`（engineFor / geniexTag）、`engine/resources/LiveResources.kt`、`TierReload.kt`、两个 `EngineFactory.kt`、设置页 / 模型设置 / 模型列表 / 选择器、开源许可列表、`V178Test`（新键 ggufEngine）。
- 未改 `QualcommNpuRuntime.kt`、`LiteRtEngine.kt`、`NpuIds.kt`。

## 需真机验证

1. 下载 → 校验 → 解包 → 「已安装」；删除 / 修复；断网时换镜像。
2. Q4_0 GGUF 在 NPU：确认 logcat `GenieX` 无插件注册错误、`ADSP_LIBRARY_PATH` 生效（FastRPC 找到 `libggml-htp-v79.so`）、速度明显快于 CPU；GPU / CPU 也各跑一次；停止按钮立即生效。
3. 多轮、编辑后重新生成、换系统提示。
4. VLM：Qwen3-VL GGUF + mmproj 读图。
5. AI Hub QAIRT 包（如 Qwen3-4B-Instruct-2507 的 SM8750 Genie 包）：加载、流式、多轮；确认不需要 libQnnHtpPrepare。
6. 先用 LiteRT NPU 模型再加载 QAIRT 包时出现「请重启」提示；反过来（先 GenieX QAIRT 再 LiteRT NPU）未做拦截，需观察。
7. llama.cpp / MNN / LiteRT 原有模型行为不变。

## 构建结果

- `testFullDebugUnitTest`：691 项全过（原 667 + V220GenieXTest 23 + V178Test 新增 1）。
- `assembleFullRelease`：`app-full-release.apk` 30,998,761 字节（2.1.0 为 30,840,019，+155 KB，只多了 GenieX 的 Kotlin 层），APK 内无任何 GenieX .so；签名证书 SHA-256 `54:21:97:77:…:3E:A4` 不变。versionCode / versionName 未改（178 / 2.1.0）。
