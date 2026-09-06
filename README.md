# QwenBboxDraw

基于 **Qwen-VL（阿里云百炼 / DashScope）OpenAI 兼容接口** 的 Android 物体定位（`bbox_2d`）工具。给一张图片，调用视觉大模型返回画面中出现的物体及其二维边界框。

纯原生 Android 构建（**无 Gradle**），用 `aapt2` / `javac` / `d8` / `apksigner` 编译成单个 APK。

## 功能

- **物体定位（bbox_2d）**：发送图片与自定义提示词，模型返回 JSON 格式的物体标签和边界框，App 内以列表形式展示。
- **供应商管理**：内置「百炼（DashScope）」与「DeepSeek」两家预设供应商，可在 App 内添加/修改/删除，设置页开下拉菜单即可切换。
- **模型列表获取**：一键从当前供应商获取可用模型列表（OpenAI 兼容 `GET /models`），下拉选择，仍支持手动输入模型名。
- **提示词模板**：支持新建、复制、重命名、编辑、删除模板，方便保存常用定位提示词。
- **高级参数**：思考开关、请求超时、失败重试次数、指数退避。
- **结果查看**：模型返回的原文可点开全屏细看。

## 隐私说明

- 源码**不含**任何真实 API Key（`ProviderConfig.DEFAULT_API_KEY` 为空）。密钥请在各供应商的「⚙ 管理供应商」里手动填写。
- 密钥保存在**设备本地私有数据目录**（SharedPreferences），不会随 APK 分发、不会被提交到仓库。
- `AndroidManifest.xml` 已设置 `allowBackup="false"`，密钥不会随系统云备份上传。

## 构建

需要 Java 与 Android SDK 平台 jar（`android-all-14-10818077.jar`，放到 `$HOME/.cache/android-sdk/`），以及 `build-apk` / `aapt2` / `d8` / `apksigner` 工具链。

```bash
bash build.sh
```

产物为 `./QwenBboxDraw.apk`。构建末尾会自检 dex 中的关键类是否齐全。

安装（可用 adb 无线调试）：

```bash
adb install -r ./QwenBboxDraw.apk
```

## 运行测试

```bash
bash run-tests.sh
```

覆盖 `BboxParser`（坐标解析）、`keyIdentity`（密钥脱敏）等 JVM 逻辑。

## 使用流程

1. 打开 App，进入「⚙ 管理供应商」，为所用供应商填入 API Key。
2. 在设置页下拉选择供应商（端点自动填充）。
3. 如需可先点「获取模型列表」选模型，或直接手动输入模型名。
4. 选择图片、写提示词（可用已保存模板），点运行，查看返回的 bbox 结果。

## 许可证

[MIT](LICENSE)
