# 构建说明

两种东西要构建：**native exploit（来自上游 GhostLock，打我们的补丁）** 和 **App**。
都不需要 Gradle。

## 0. 环境

| 依赖 | 版本/说明 |
|---|---|
| Android SDK build-tools | 36.x（提供 `aapt2 / d8 / apksigner / zipalign`） |
| Android platform | `platforms/android-36/android.jar` |
| JDK | 17+（`javac`、`keytool`） |
| Android NDK | 27.x（`ANDROID_NDK_HOME` 指过去） |
| 网络 | 构建 App 时会从 Maven Central 拉 Shizuku 的 3 个 AAR（13.1.5） |

## 1. 构建 native exploit

```sh
git clone https://github.com/YuKongA/ghostlock-app.git
cd ghostlock-app
git checkout 4bcf7043da542bc1c46fe0d3fd7dc336928218c4   # 本仓库适配时对应的上游 commit

git apply /path/to/tb320fc-temp-root/native/patches/ghostlock-5.10.209-tb320fc.patch

export ANDROID_NDK_HOME=$HOME/Library/Android/sdk/ndk/27.2.12479018
make -C src ghostlock API=34
# 产物：build/native/ghostlock
```

或者用脚本：`bash native/build.sh`（内容就是把上面几步串起来，检查 NDK 与 patch 是否就位）。

补丁做了什么（详细见 `docs/PRINCIPLE.md` §5）：

1. `src/core/kernel/target_constants.hpp`：`kImageTextBase` 改 `0xffffffc008000000`（连同 static_assert）；
2. `src/Makefile`：加 `-D__BIONIC_NO_PAGE_SIZE_MACRO`（NDK 27 的 `bits/page_size.h` 定义 `PAGE_SIZE`，与仓库常量撞名）；
3. `main.cpp`：`setvbuf(stdout, nullptr, _IOLBF, 0)`（否则日志块缓冲，进程被 kill 会丢尾部）；
4. `race/threads.cpp`：加 `reset begin/done` 两行日志；
5. `session/victim_process.cpp`：把 6 个 victim 管道端 `fcntl(F_DUPFD, 400)` 抬到 400 以上（否则被 select 路由的 `destroy()` 关掉）+ W2 校验失败时打印原因；
6. `attack/ops.cpp`：root 脚本里加 sysctl 降损块、`.ghostlock_status` 存活标记（**注意整段脚本是 snprintf 的 format，字面 `%` 必须写成 `%%`**）、以及脚本生成后的自检。

## 2. 生成 profile（v2 二进制）

```sh
SEED=50000 python3 profile/mkprofile.py      # 产物写在脚本同目录：tb320fc.bin
cp profile/tb320fc.bin app/src/main/assets/profile.bin
```

`profile/mkprofile.py` 把 HOCON 里的字段编码成 native 读的格式：
`u32 magic(0x0D000721) + u16 version(2) + u16 frontend + u16 backend + u16 route + u16 release_len + 2 字节保留`，
随后是 `u16 section_count` 与各 `(name, entries[key, u64])`。

> 踩过的坑：头部是 **16 字节**（有 2 字节保留），我第一次按 14 字节写，结果 native 报 `cannot load profile`。

## 3. 构建 App

```sh
cp <ghostlock 构建产物> app/src/main/assets/ghostlock-dev
cd app
./build.sh
# 产物：app/tb320fc-ghostroot.apk
```

`app/build.sh` 做的事：拉/复用 Shizuku 三个 AAR → `javac` → `d8` → `aapt2 compile/link`（带 assets）
→ 打进 `classes.dex` → `zipalign` → `apksigner` 签名（首次会生成 `ghostroot.keystore`）。

App 侧的模块：

| 文件 | 职责 |
|---|---|
| `MainActivity.java` | 状态卡（是否已临时 root / KernelSU / SELinux / 内核 / 本轮进度）+ 一键按钮 + 实时日志 + 日志落盘 |
| `Runner.java` | 通过 Shizuku 的 `IShizukuService.newProcess` 以 shell 身份推二进制、跑 exploit、超时清理、`svc power reboot` |
| `Status.java` | 状态判定：root 脚本写的 boot_id 标记 → Shizuku 查 `/proc/modules` → `/sys/module` 兜底 |
| `BootReceiver.java` | 自动重启后发通知，把用户拉回来继续 |
| `RunReceiver.java` | 无界面入口（`am broadcast`），便于排障/自动化 |

## 4. 安装与自检

```sh
adb install -r app/tb320fc-ghostroot.apk
# 装完打开 App，应显示：未 root / KernelSU 模块：未加载 / 内核：5.10.209-…
```

## 5. 发布新版本

1. `dist/tb320fc-ghostroot-vX.apk` 更新；
2. `README.md` 的成功率/行为表如果变了同步更新；
3. GitHub Release 里附 APK + 一段"约束条件 + 已知失败行为"的说明；
4. 如果动了 native 参数，务必同步更新 `docs/PARAMS.md` 与 `profile/*.conf`。
