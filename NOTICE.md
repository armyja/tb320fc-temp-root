# NOTICE — 第三方组件与许可

本仓库自有代码（`app/`、`profile/`、`docs/`）以 **Apache License 2.0** 发布，见 `LICENSE`。

本仓库**包含或依赖**以下第三方作品，其版权归各自作者所有：

## 1. GhostLock（核心漏洞利用实现）

- 项目：<https://github.com/YuKongA/ghostlock-app>
- 许可：**Apache License 2.0**
- 本仓库的用法：
  - `dist/tb320fc-ghostroot-v4.apk` 内含基于其源码构建的 native 二进制
    `assets/ghostlock-dev`，**已按本设备适配修改**；
  - 修改内容以 patch 形式完整公开：`native/patches/ghostlock-5.10.209-tb320fc.patch`
    （对应上游 commit `4bcf7043da542bc1c46fe0d3fd7dc336928218c4`）；
  - 未修改其许可证、未移除其版权声明。
- 若要重新分发本仓库的 APK，请保留本 NOTICE 与 `LICENSE`，并保留上游 Apache-2.0 声明。

## 2. Shizuku（App 的 shell 通道）

- 项目：<https://github.com/RikkaApps/Shizuku>
- 许可：**Apache License 2.0**
- 用法：构建时从 Maven Central 拉取 `dev.rikka.shizuku:api/provider/aidl:13.1.5` 并打包进 APK，
  **未做任何修改**。

## 3. KernelSU（可选，root 管理器）

- 项目：<https://github.com/tiann/KernelSU>（及 ReSukiSU / BakaSU / KowSU 等衍生）
- 许可：**GPL-2.0**
- 用法：**本仓库不分发 KernelSU**。你需要自行从其官方 Release 安装；
  root 脚本通过其 `ksud` 二进制执行 `late-load --kmi android12-5.10` 加载 `kernelsu.ko`。

## 4. Android NDK / SDK 组件

- 构建期使用 Android SDK build-tools 与 NDK，按各自许可使用，未随本仓库分发。

## 5. 设备参数与固件

- `profile/` 与 `docs/PARAMS.md` 中的偏移/地址来自对**设备自带固件**的静态分析，
  不包含任何厂商代码或二进制片段；`docs/iomem-5.10.209.sample.txt` 是本机
  `/proc/iomem` 的文本快照，仅作排障参考。

---

如需移除其中任何组件，请在本仓库提 issue；涉及上游许可的部分以各自许可为准。
