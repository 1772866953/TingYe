# 本地签名配置

签名密钥和真实密码仅保存在本机，不提交到公共仓库。

1. 准备自己的 `.jks` 或 `.keystore` 签名文件。
2. 将 `release.properties.example` 复制为本目录的 `release.properties`。
3. 填写签名文件路径、别名和密码。
4. 在 `TingYe/` 内执行 `./gradlew assembleRelease`（Windows 使用 `gradlew.bat`）。

默认读取仓库根目录的 `signing/release.properties`。也可以通过环境变量 `TINGYE_SIGNING_PROPERTIES` 指定其他配置文件的绝对路径。

配置中的 `storeFile` 相对 **Android 项目根目录 `TingYe/`** 解析。例如密钥放在本目录时，可填写 `../signing/your-release.jks`，也可以填写绝对路径。

请自行安全备份密钥与配置。应用覆盖升级需要使用同一签名；仓库作者的正式密钥不会公开。没有本地开发签名文件时，调试构建使用 Android 工具链生成的本机调试签名。
