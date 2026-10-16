# 听页 TingYe

面向本地有声书的 Android 播放器，使用 Kotlin、Jetpack Compose 和 Media3 开发。

当前源码版本：**0.1.8**。最低支持 Android 8.0（API 26）。

## 功能

- 多音频、文件夹及批量多书导入，章节自然排序，提取书名与封面。
- 书架搜索、排序和筛选，继续收听与迷你播放器。
- 各章节独立进度、已听标记、书签、剩余收听时间。
- 倍速、快进快退、片头片尾跳过、音量均衡。
- 按时间或章节停止的睡眠定时，以及停止前音量渐弱。
- 误跳进度恢复，提示时长可在 1–12 秒之间调整。
- 通知栏媒体控制、耳机按键自定义、桌面小组件。
- 本地收听统计、书架与设置备份。
- 壁纸、主题、动画速度及触觉反馈设置。
- 播放服务回收后的进度恢复；暂停时释放播放唤醒锁并停止无必要的进度轮询。

移除书籍仅删除应用内记录，不删除源音频。厂商后台管理和锁屏显示可能影响实际表现。

## 获取源码

```sh
git clone https://github.com/1772866953/TingYe.git
cd TingYe
```

使用 Android Studio 打开仓库内的 **`TingYe/`** 目录。

## 构建

环境要求：JDK 17、Android SDK Platform 36、Build Tools 35.0.0。已包含 Gradle 8.13 Wrapper。

在 Android Studio 中配置 Android SDK，或通过 `ANDROID_HOME` / 项目内的 `local.properties` 指定 SDK 路径。首次构建需要下载 Gradle 和依赖。

在 Android 项目目录执行：

```sh
cd TingYe
./gradlew testDebugUnitTest assembleDebug
```

Windows 使用 `gradlew.bat`。调试 APK 输出到 `TingYe/app/build/outputs/apk/debug/`；调试签名由本机生成。

### 正式签名

仓库不包含签名密钥或真实密码。需要自行准备签名文件，并参考[签名配置说明](signing/README.md)填写本地配置。

```sh
cd TingYe
./gradlew testReleaseUnitTest assembleRelease
```

正式 APK 输出到 `TingYe/app/build/outputs/apk/release/`。自行生成的签名通常不能直接覆盖其他签名安装的版本，操作前应导出应用内备份。

`TingYe/scripts/build-release.ps1` 是原开发工作区的正式交付脚本，依赖本机的 `tools/` 和签名配置；普通克隆请使用上述 Gradle Wrapper 命令。

## 项目结构

```text
TingYe/
  app/src/main/       应用源码、资源与预编译配置
  app/src/test/       单元测试
  app/schemas/        Room 数据库历史结构
  gradle/wrapper/    Gradle Wrapper
  scripts/           构建、性能采集与模拟器验证脚本
  docs/              性能维护说明
  DESIGN.md          当前产品及交互约束
signing/
  release.properties.example  签名配置模板
qa/README.md         测试资料说明
```


## 开发资料

- [产品与交互约束](TingYe/DESIGN.md)
- [应用架构与运行说明](TingYe/README.md)
- [性能与设备维护](TingYe/docs/device-performance.md)

界面、通知、耳机和小组件共用 Media3 播放服务。数据库迁移及各章节播放进度应在迭代中保留；动画相关修改需检查完整的展开与收回过程。
