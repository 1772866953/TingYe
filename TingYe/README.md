# 听页 TingYe

简体中文离线有声小说播放器。音频来自用户选择的本地文件；应用不申请网络权限。

## 环境与构建

- 完整 JDK 17 或 21（需要 `jlink`）；Android SDK Platform 36、Build Tools 35.0.0。最低支持 Android 8.0，目标 API 36。
- 通过 Android Studio 或项目内的 Gradle Wrapper 构建；首次构建需要下载 Gradle 和固定版本依赖。完整构建与签名说明见[仓库主页](../README.md)。
- Gradle 构建的 APK 输出到 `app/build/outputs/apk/`。本机工具链和预先生成的安装包不纳入源码仓库。
- MuMu：运行 `powershell -File scripts/mumu-adb.ps1 -Action status` 确认 API 32 设备，再运行 `powershell -File scripts/mumu-adb.ps1 -Action install`。
- 此 MuMu 实例也可能显示为 `emulator-5554`；脚本固定使用 `127.0.0.1:16384`，避免设备列表中出现两个入口时误装到其他设备。若 ADB 报 `Cannot mkdir '\.android'`，应在能访问 Windows 用户配置目录的常规终端运行脚本。

## 使用

在书架选择多个音频文件或一个包含子目录的文件夹。导入时会按章节名中的数字自然排序。打开书籍后可播放、跳转章节、改变倍速和设置定时停止。设置页可选择浅色、深色、跟随系统与跳转秒数。书架的移除操作只清除应用记录，不删除源文件。

若源文件被移动、删除或文件提供方撤销了 URI 许可，播放前会提示重新定位该章节。文件格式最终取决于 Android/Media3 在当前设备上的解码能力。模拟器上的播放表现不代表所有蓝牙耳机或厂商后台管理策略。

## 架构

- `data/Library.kt`：Room 实体、DAO、导入扫描、自然排序。
- `data/Preferences.kt`：DataStore 收听偏好。
- `playback/PlaybackService.kt`：Media3 ExoPlayer、MediaLibrarySession、通知、后台播放、定时停止与逐章进度写入。
- `MainViewModel.kt`：MediaController 与页面状态桥接；Activity 不直接持有播放器。
- `MainActivity.kt`：Compose 页面与系统文件选择器。

播放器界面现在以单一覆盖层承载。书籍行、继续收听卡片和迷你播放器分别提供实时布局锚点；同一个 `Animatable` 驱动容器与封面的展开、下拉收起和系统返回，底层书架保留滚动状态。通知入口使用无来源的直接呈现。详细参数、返回规则和未验证场景见 `DESIGN.md`，性能维护说明见 `docs/device-performance.md`。

播放状态以 Media3 Player 为唯一来源。页面通过 MediaController 订阅；通知和外部媒体命令通过 MediaSession 作用于同一 Player。服务在暂停、切章、结束、销毁与播放中定期保存位置。重开应用时默认不自动出声。

版本与系统行为依据：[AGP 8.13 兼容表](https://developer.android.com/build/releases/agp-8-13-0-release-notes)、[MediaSessionService 后台播放](https://developer.android.com/media/media3/session/background-playback)、[SAF 持久 URI 权限](https://developer.android.com/training/data-storage/shared/documents-files)、[Google Play 目标 API 要求](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en)。

## 发布

当前版本 0.1.8，正式构建开启代码与资源裁剪。原工作区的 `scripts/build-release.ps1` 依赖本机工具链，并将交付文件保存到 `../releases/0.1.8/`；普通克隆使用 Gradle Wrapper 构建。真实签名文件与配置不提交仓库，仅提供[配置模板](../signing/release.properties.example)。

支持章节已听标记、耳机上一首／下一首自定义、桌面播放器小组件、导入书名与封面预览、简洁收听统计、音量均衡，以及可配置 1–12s 的进度恢复提示。快进快退、上下章和进度条提交后自动播放；恢复刚才位置保留当前播放／暂停状态。数据库支持旧版本连续升级，保留章节进度。

构建时加 `-Test` 会运行单元测试后生成正式包。`scripts/check-listening-features.py` 用于受控 MuMu 测试库的定向验证；`scripts/prepare-metadata-fixture.py` 生成可复用的元数据与封面测试素材。

仓库结构与构建说明见上级 README。模拟器脚本依赖原测试环境和自行准备的音频；本机工具链、测试音频、安装包与混淆映射不纳入源码仓库。
