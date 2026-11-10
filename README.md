# 谛读 (DiDu)

一个只保留必要功能的 Android 电子书阅读器。安装包约 0.1MB；只在你主动下载离线语音时联网。

## 功能

- 导入 `.txt`（UTF-8 / UTF-16 / GBK 自动识别，自动按“第 X 章”分章）和 `.epub`（文字与封面）
- 书架：继续阅读卡片、封面网格；没有封面的书自动生成一张
- 自动保存每本书的阅读进度
- 排版：字号、行距、段距、边距、黑体 / 宋体、首行缩进；中文两端对齐
- 六种配色（纸张、素白、豆绿、墨蓝、夜间、烛光），一键切换日间 / 夜间
- 翻页动画：平移、覆盖、上下、无；点击翻页可选“左右翻页”或“全屏向后”，点屏幕中间调出菜单；可用音量键翻页
- 自动翻页：下一页从上到下逐渐盖住当前页，文字本身不移动；速度可调（每页 5–120 秒）
- 听书：菜单第五项“听”打开听书面板（播放 / 暂停、上一句 / 下一句、语音、语速）；
  阅读时双击任意一段从那里开始听；听到的位置单独记忆，下次从“听”进入会接着上次继续。
  默认使用手机系统自带的语音引擎，也可导入离线语音模型，使用内置的女声 / 男声

## 离线语音

离线女声 / 男声使用 [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)（Apache-2.0）和它发布的
zh-ll 多说话人 VITS 模型（16kHz）。引擎（约 27MB 原生库）和模型都不在安装包里，装好应用后再获取，
所以安装包只有约 0.1MB。在阅读界面点屏幕中间 → “听” → “离线语音”，有两种方式：

- **自动下载**：从 sherpa-onnx 的 GitHub 发布页下载引擎（实际传输约 12MB）和模型（约 113MB）。
  应用只在这一步联网。
- **手动导入**：自己下载下面两个文件，在应用里一起选中。已经装好的那一个不用重复导入。
  - 引擎：[v2.0.1](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.1) 里的 `sherpa-onnx-2.0.1.aar`，
    或任何包含其中 arm64 的 `libonnxruntime.so` 与 `libsherpa-onnx-jni.so` 的 zip
  - 模型：[tts-models](https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models) 里的
    `sherpa-onnx-vits-zh-ll.tar.bz2`

引擎是原生代码，只有 SHA-256 与 `OfflineVoice.kt` 中固定的值一致才会被安装和加载，因此只接受
sherpa-onnx 2.0.1 的 arm64 版本。`com/k2fsa/sherpa/onnx/Tts.kt` 是该版本的 Kotlin 绑定，升级引擎时
两处要一起更新。

合成过的句子会缓存在磁盘上（默认上限 100MB，约 50 分钟语音，最近最少使用的先淘汰），
重听同一段不需要再合成；上限和清空都在“听”面板里。

女声、男声分别是模型里的 2 号和 1 号说话人（见 `SherpaModel.kt`）。这个模型比系统语音慢得多：
在 x86 上 4 线程合成耗时约为音频时长的 0.2 倍，手机上会更高，较旧的手机可能出现句间停顿。

## 构建

需要 JDK 17+ 和 Android SDK（platform 36、build-tools 36.0.0）。

```bash
./gradlew assembleRelease
```

```bash
./gradlew testDebugUnitTest
```

APK 输出在 `app/build/outputs/apk/release/app-release.apk`。release 版本开启了代码压缩，
并暂时用调试密钥签名以便直接安装；正式发布前请换成自己的签名。

测试包含对真实界面的渲染测试（Robolectric）。`GalleryTest` 会把每个页面在明暗两种配色下
渲染到 `app/build/gallery/`；设置环境变量 `DITING_SAMPLE_EPUB` 可以用一本真实的 EPUB 作为内容。
设置 `DITING_NETWORK_TEST=1` 会真实执行一次离线语音的自动下载。

## 已知限制

- 朗读没有后台服务：锁屏或切到后台后，系统可能随时停止朗读
- EPUB 正文中的图片、样式、脚注不显示
- “上下”翻页是整页上下滑动，不是连续滚动
