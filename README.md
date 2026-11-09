# TB320FC 临时 root（GhostLock 适配）

联想 TB320FC（Qualcomm SM8475 / cape，ZUX OS 1.1.350）的一键**临时 root** App，
基于 [GhostLock](https://github.com/YuKongA/ghostlock-app)（CVE-2026-43499）做设备适配。

> **这是一个设备适配项目，不是通用 root 工具。** 换机型/换固件必须重新提取参数（见 [docs/PARAMS.md](docs/PARAMS.md)）。

- App 源码：`app/`（纯 Java，不用 Gradle，`aapt2 + javac + d8 + apksigner` 直接出包）
- 设备适配参数：`profile/`（HOCON + 打包脚本）
- 对上游 GhostLock 的改动：`native/patches/`
- 预编译 App：`dist/tb320fc-ghostroot-v4.apk`

---

## 一、适用范围（必须精确匹配，否则**不要**用）

| 项 | 要求 | 怎么核对 |
|---|---|---|
| 机型 | **联想 TB320FC**（Qualcomm SM8475 / cape） | 设置里看型号 |
| 系统 | **ZUX OS 1.1.350**（`TB320FC_CN_OPEN_USER_Q00031.0_V_ZUXOS_1.1.350_ST_250418`） | `getprop ro.build.display.id` |
| 内核 | **`5.10.209-android12-9-gef44381c3e04-ab50`**（必须逐字一致） | `uname -r` |
| bootloader | 已锁、验证启动正常（本方案**不需要**解锁） | `getprop ro.boot.flash.locked` = 1 |
| 额外依赖 | **Shizuku**（App 用它拿 shell 身份与重启权限）；**KernelSU**（可选，用于可交互 su / 加载模块） | — |

**以下情况不要用**：内核版本不一致（哪怕只差一个 build 号）、刷过第三方内核/Magisk、
非本人设备、对稳定性有硬性要求的生产用途。

---

## 二、它能做到什么、不能做到什么

**能**：在**不改任何分区**的前提下，把当前这一"开机"变成 uid 0 可用（SELinux 由脚本在收尾时恢复 Enforcing，
KernelSU 以 LKM 方式 late-load），重启后完全消失。

**不能**：持久 root、解锁 bootloader、绕过设备锁、改 system/vendor、在别的机型上用。

---

## 三、快速开始

### 方式 A：装 App（推荐）

```sh
adb install -r dist/tb320fc-ghostroot-v4.apk
```

1. 先让 **Shizuku** 跑起来（App 内「通过无线调试启动」，或用 adb 拉起 `libshizuku.so`）；
2. 打开「TB320FC 临时 root」→ 点 **一键获取临时 root**；
3. 首次会要通知权限（用于"重启后继续"），Shizuku 弹窗选 **始终允许**；
4. 成功标志：标题变 **已临时 root**，KernelSU 里显示「工作中 [越狱模式]」。

App 的行为是：**一次开机只试一次，失败自动重启**（竞态会把内核状态弄脏，同一开机内重试基本无效），
开机后通知把你拉回来继续，最多 8 次开机。

### 方式 B：命令行（调试用）

```sh
# 需要自己构建 native（见 docs/BUILD.md），或用 App 里的 assets/ghostlock-dev
adb push ghostlock-dev /data/local/tmp/ghostlock-dev
adb push profile.bin  /data/local/tmp/profile.bin
adb shell 'chmod 755 /data/local/tmp/ghostlock-dev; sync'
adb shell 'timeout 120 /data/local/tmp/ghostlock-dev --load-prebuilt-profile /data/local/tmp/profile.bin'
```

成功时依次看到：

```
[+] SELinux permissive
[T+…ms] Write 1 complete
child uid = 0
[+] child is root!
[*] late-load kmi=android12-5.10
[+] KernelSU module loaded
```

---

## 四、成功率与失败行为（先读这一段，避免误判）

| 指标 | 实测 |
|---|---|
| 单次路由尝试（W1 或 W2 各一次）成功 | 约 1/3 |
| 单次完整运行走完 W1+W2+handoff+KSU | 约 1/4（6 次成功 / 约 25 次完整运行） |
| 失败形态 | 约 2/3 卡住（不重启，内核半死，需重启）；约 1/3 直接 panic 重启 |
| 会不会变砖/丢数据 | **不会**。全程只改运行内存，不写分区 |
| 期望拿到 root 的时间 | 顺利 15–25 秒；典型 3–8 分钟；运气差 10 分钟上下 |

**单次失败是正常现象，不是坏了。** App 会自动重启继续。

---

## 五、临时 root 之后：什么不能动 / 什么可以动

**绝对不要**：

1. kill 掉承载 root 的进程（`ps` 里 `ghostleaf_*` 和 `root` 的 `sh`）——W2 把 `task->cred` 指向静态
   `init_cred` 且没加引用计数，这些进程退出会让内核 `kfree` 掉 `init_cred`，后续权限检查可能直接崩；
2. `rmmod kernelsu`——LKM 是 hot-load 进运行内核的，不为卸载设计；
3. fastboot 刷任何分区、解/锁 bootloader——本方案完全不需要刷机；
4. 再装第二个 root 管理器（Magisk / 另一个 KSU 变体）。

**可以**：正常使用设备、用 KernelSU 给 App 授权 su、只读检查（`getenforce`、`/proc/modules`、`ps`）、
随时重启（重启即恢复出厂运行状态）、重新跑一次 App 再拿 root。

完整说明见 [docs/SAFETY.md](docs/SAFETY.md)。

---

## 六、目录结构

```
app/                App 源码（Java）+ build.sh（不用 Gradle 直接出 APK）
  src/main/assets/  随包的 ghostlock-dev（native exploit）与 profile.bin
native/patches/     对上游 GhostLock 的源码改动（git diff，Apache-2.0）
profile/            本机 HOCON 参数 + mkprofile.py（打成 native 读的 v2 二进制）
docs/               原理、参数、构建、排障、安全边界
dist/               预编译 APK（Release 附件）
```

## 七、文档

| 文档 | 内容 |
|---|---|
| [docs/PRINCIPLE.md](docs/PRINCIPLE.md) | 漏洞原理与提权链路（W1 / W2 / handoff），为什么本机必须走 select 路由 |
| [docs/PARAMS.md](docs/PARAMS.md) | 本机全部参数与**来源**（每个偏移在哪条指令里量到的） |
| [docs/BUILD.md](docs/BUILD.md) | 如何从上游源码构建 native、如何构建 App、如何打包 profile |
| [docs/TROUBLESHOOT.md](docs/TROUBLESHOOT.md) | 已知问题与排查（含我们踩过的 7 个坑） |
| [docs/SAFETY.md](docs/SAFETY.md) | 安全边界：能做什么、不能做什么、失败后果 |

## 八、免责声明

- **仅限在你自己拥有的设备上使用。** 请勿用于他人设备、租借设备或任何你不是机主的设备。
- 这是**研究/复现**性质的工具，按 Apache-2.0 "AS IS" 提供，**不提供任何担保**。
- 已知副作用：竞态失败会导致内核 panic 并**自动重启**（不丢数据），偶发卡死需要手动重启。
- 作者不对因使用本工具造成的任何直接或间接损失负责。

## 九、许可与致谢

- 本仓库自有代码：**Apache-2.0**（见 `LICENSE`）。
- 核心漏洞利用实现来自 [GhostLock](https://github.com/YuKongA/ghostlock-app)（Apache-2.0），
  我们只做了设备适配（patch 见 `native/patches/`），并保留其版权声明，详见 [NOTICE.md](NOTICE.md)。
- [Shizuku](https://github.com/RikkaApps/Shizuku)（Apache-2.0）：App 通过它获得 shell 通道；
  AAR 在构建时从 Maven Central 拉取，未修改。
- [KernelSU](https://github.com/tiann/KernelSU)（GPL-2.0）：**不随本仓库分发**，
  需要你自行从其官方 Release 安装，脚本通过其 `ksud` 完成 `late-load`。
