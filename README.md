# 墨墨工具箱 Android 工程

当前应用 ID：`com.openai.wordmeter.v5`  
初始更新器版本：`5.2.0`  

包含背词速度计、计算器与单位换算、饮食打卡，以及基于 GitHub Releases 的应用内更新功能。

## 第一次安装带更新器的版本

1. 将工程文件提交并推送到 GitHub 仓库 `shilan660/-`。
2. 打开仓库的 **Actions → Build Android release APK → Run workflow**。
3. 等待成功后，在运行记录的 **Artifacts** 下载 `maimemo-toolbox-v5.2.0`，解压并安装 APK。
4. 必须使用仓库 Actions Secrets 中同一份签名密钥构建；不要更换 `applicationId` 或签名密钥，否则 Android 不能原位更新。

## 发布正式版本，供应用内检查更新

更新器通过 GitHub 的最新正式 Release 获取版本信息，并下载该 Release 中的 APK 附件。正式版本必须使用 Git tag 发布：

1. 先提交并推送源码修改。
2. 在 GitHub 仓库打开 **Releases → Draft a new release**。
3. 创建一个新的版本标签，例如第一次使用更新器时创建 `v5.2.0`，之后每次发布递增为 `v5.2.1`、`v5.3.0` 等。
4. 点击 **Publish release**。发布后，`Publish Android APK Release` 工作流会按 tag 自动设置 Android `versionName` / `versionCode`、编译签名 APK，并把 `app-release.apk` 附加到该 Release。
5. 第一次需要从该 Release 手动下载并安装 `app-release.apk`，因为旧版本还没有应用内更新功能。此后打开应用首页的 **检查应用更新**，即可检查、下载和安装后续正式版本。

版本标签必须是 `v主版本.次版本.修订版本` 格式，例如 `v5.2.1`。以后不要重复使用已经发布过的 tag。

## Android 安装说明

Android 出于安全原因会显示系统安装确认界面；首次使用时，可能需要允许“墨墨工具箱”安装未知来源应用。应用内更新会检查、下载 APK 并打开系统安装界面，不会静默安装。

饮食打卡等本地数据保存在应用本机存储中。正常覆盖安装且应用 ID 不变时，数据通常会保留；卸载应用会清除应用私有数据。
