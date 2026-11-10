# 谛读 (DiDu)

一个只保留必要功能的 Android 电子书阅读器。不联网，不申请任何权限。

## 功能

- 导入 `.txt`（UTF-8 / UTF-16 / GBK 自动识别，自动按“第 X 章”分章）和 `.epub`（文字与封面）
- 书架：继续阅读卡片、封面网格；没有封面的书自动生成一张
- 自动保存每本书的阅读进度
- 排版：字号、行距、段距、边距、黑体 / 宋体、首行缩进；中文两端对齐
- 六种配色（纸张、素白、豆绿、墨蓝、夜间、烛光），一键切换日间 / 夜间
- 翻页动画：平移、覆盖、上下、无；可用音量键翻页
- 自动翻页：下一页从上到下逐渐盖住当前页，文字本身不移动；速度可调（每页 5–120 秒）
- 朗读：默认使用手机系统自带的语音引擎；也可导入离线语音模型，使用内置的女声 / 男声
- 两套界面风格可在设置里切换：Material 与 Soft

## 离线语音

离线朗读使用 [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)（Apache-2.0）和它发布的
aishell3 多说话人 VITS 模型。模型不随安装包分发，需要手动导入一次：

1. 从 <https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models> 下载
   `vits-icefall-zh-aishell3.tar.bz2`（约 32MB）
2. 阅读界面点屏幕中间 → “设置” → “朗读”下的“导入模型” → 选择该文件
3. 在“语音”一行选择“女声”或“男声”

说明：该模型输出为 8kHz 采样率，音质不如系统语音清晰。女声、男声分别是模型里的
24 号和 136 号说话人，是按实测基频挑选的（见 `SherpaModel.kt`）。

## 构建

需要 JDK 17+ 和 Android SDK（platform 36、build-tools 36.0.0）。

```bash
./gradlew assembleDebug
```

```bash
./gradlew testDebugUnitTest
```

APK 输出在 `app/build/outputs/apk/debug/app-debug.apk`。安装包只包含 arm64-v8a 的原生库。

测试包含对真实界面的渲染测试（Robolectric）。`GalleryTest` 会把每个页面在两套风格、明暗两种配色下
渲染到 `app/build/gallery/`；设置环境变量 `DITING_SAMPLE_EPUB` 可以用一本真实的 EPUB 作为内容。

## 已知限制

- 朗读没有后台服务：锁屏或切到后台后，系统可能随时停止朗读
- EPUB 正文中的图片、样式、脚注不显示
- “上下”翻页是整页上下滑动，不是连续滚动
