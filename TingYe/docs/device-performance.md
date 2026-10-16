# 性能与设备维护

## 构建

正式交付使用 release，开启 R8 和资源裁剪。`scripts/build-release.ps1` 只生成正式签名包，并保存混淆映射。开发与性能采集使用 debug / profile 测试签名。

## 动画关键路径

- 播放器和设置组成保留至 Activity 结束；隐藏节点测量但不放置，不能拦截点击或无障碍。
- 设置退出画缓存图层、取消真实控件放置；关闭后分组收起、滚动复位。
- 移动文字缓存排版结果并画在 Canvas。PlayerVisual 在图层读取进度并保持对象身份，避免逐帧控件重组。
- 位图在后台解码并 prepareToDraw。内存缓存 24MiB；可信修订时间的图片磁盘缓存 32MiB，否则绕过磁盘缓存。
- 进程结束后 GPU 纹理与 Compose 树仍须重建，图片缓存、组成保留和 ART 预编译分别减少不同开销。
- 固定书籍设置视口和时长行数；在边界消耗向上滚动及 fling，保留向下关闭。
- 震动系统读取与调用在顺序后台线程，丢弃过时请求。选择反馈为 12ms、操作反馈为 18ms 单脉冲；强度调幅，回调反馈与包装器反馈不叠加。

## 预编译资料

`app/src/main/baseline-prof.txt` 保留真实操作采集的规则，依赖库规则单独合并，R8 在正式构建中重写规则。关键路径变化后才重新采集，无需为每次文案或入口删除重复采集。

准备 API32、1080×1920、density3 测试模拟器，安装 profile 构建并导入 `../qa/fixtures/batch-audio/MorningBook` 等测试书籍，执行 `scripts/train_profile.py`、`scripts/export_profile.py`。导出需要测试模拟器 root，结果在 `../qa/results/profiles/`。

## 按需验证工具

- `quick-device-check.py`：书籍设置入口、播放控制单次震动和媒体通知。
- `measure_cold_actions.py 标签`：六段操作帧时间，输出到 `qa/results/performance/`。
- `record_three_origins.py`：三来源往返录制，输出到 `qa/results/animations/`。
- `video_contacts.py 标签`：生成录制联系图，需要 Pillow。
- `ui_probe.py`：查看控件、按名称点击、截图。

这些脚本位于 scripts，依赖准备好的测试书架，不直接对用户手机运行。录制检查运动交接，逐帧时间检查性能；模拟器不能确认真实马达、VIVO 锁屏策略或真机 120Hz，GIF 的 50fps 导出不代表实际设备帧率。

通知参考：[MediaSessionService](https://developer.android.com/media/media3/session/background-playback)、[Media3 通知实现](https://github.com/androidx/media/blob/1.10.0/libraries/session/src/main/java/androidx/media3/session/DefaultMediaNotificationProvider.java)。
