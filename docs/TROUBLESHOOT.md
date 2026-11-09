# 排障手册（都是我们自己踩过的坑）

按"现象 → 定位手段 → 根因 → 修法"排列。前 6 个坑已经修在 `native/patches/` 里，
第 7、8 个修在 App 源码里。

---

## 坑 1：`cannot load profile`

- **现象**：CLI 直接拒绝加载 profile。
- **定位**：读上游 `src/core/profile/binary.h` 的头定义：`u32 magic + u16 version + u16 frontend
  + u16 backend + u16 middleware + u16 release_len`，而 `kHeaderSize = 16`。
- **根因**：头部有 2 字节保留位，`release` 字符串必须从 offset 16 开始，我按 14 字节写了。
- **修法**：补 2 字节 0。

## 坑 2：W2 永远 `payload arm mismatch ... right=0`

- **现象**：`init_cred image=… alias=0000000000000000`，随后
  `payload arm mismatch preserve_child=1 right=0000000000000000`。
- **定位**：反汇编上游发布的 `libghostlock.so`，用 `movz/movk` 重建常量：
  `0xffffffc080000000`（6.x 基址）出现 5 次，`0xffffffc008000000`（本机基址）**0 次**；
  再逐段读那 5 处，发现 alias 换算里有一个 `0x3f80000000` 偏置，配 5.10 基址会借位返回 0。
- **根因**：上游发布物是按 6.x 布局编译的。
- **修法**：改编译期基址（见 `docs/BUILD.md` §1）。改完 `alias=ffffff802a7c0ae0`，
  与手算 `PAGE_OFFSET + (phys_load − phys_offset) + offset` 一致。

## 坑 3：内存泄漏永不命中（`mm_struct leaked=0xffffffffffffffff`）

- **定位**：反汇编 `mm_cache_init`：`mov w1, #0x3c0`（`kmem_cache_create_usercopy` 的 size）。
- **根因**：`kernelsnitch.mm_struct_sz` 猜成 1024/1280。
- **修法**：改 **960**；泄漏从"每次失败"变成"第一次就命中"。

## 坑 4：W2 校验永远 `EBADF`（`W2 verify: cmd write ret=-1 errno=9`）

- **现象**：W1 成功、W2 写也跑了，但校验永远失败。
- **定位**：加打印发现 victim 管道 fd 是 6/7；再看上游 `select_stack_route.cpp` 的 `destroy()`：
  它会把 fd 3..319 中自己 `fd_set` 标记过的一律 `close()`。
- **根因**：**adb shell 进程 fd 很空**，管道正好落在 6/7 → 被路由顺手关掉。
  App 进程 fd 多，管道天然 >320，所以 App 里没这问题。
- **修法**：`spawn_child()` 里把 6 个管道端 `fcntl(F_DUPFD, 400)` 抬到 400 以上。

## 坑 5：日志"卡在 heap spray done"——假的

- **现象**：失败运行日志总停在同一个 4 KB 边界。
- **定位**：`pr_info` 就是 `printf`；stdout 重定向到文件时是**块缓冲**，进程被 SIGKILL 时缓冲区丢失。
- **根因**：测量伪影，不是真卡死。
- **修法**：`main()` 里 `setvbuf(stdout, nullptr, _IOLBF, 0)`。

## 坑 6：`ksud missing; cannot late-load`

- **现象**：root 脚本以 uid 0 跑完，但没有可交互 su。
- **根因**：设备上没装任何 root 管理器。
- **修法**：装 KernelSU（其 Release 自带 `lkm-aarch64-android12-5.10_kernelsu.ko`），
  脚本会自动 `ksud late-load --kmi android12-5.10`，之后 `/proc/modules` 出现 `kernelsu … Live (O)`。

---

## 坑 7（App）：root 成功却显示"未 root"

- **现象**：KernelSU 里已经是「工作中 [越狱模式]」，App 里还是"未 root"，重开也一样。
- **定位**：三条探测路径全被挡：
  1. `/data/local/tmp/.ghostlock_status` 属 `shell_data_file`，普通应用 SELinux 拒绝读；
  2. `/sys/module/kernelsu` 在这台内核上**不生成**（模块是 init_module 加载的）；
  3. `/proc/modules` 应用读不到。
- **修法**：App 用 **Shizuku 以 shell 身份读 `/proc/modules`**，查到就记录当时的 `boot_id`；
  之后 boot_id 不变就认定"已临时 root"，重启后 boot_id 变了自动回到未 root。

## 坑 8（App）：每次都在 1 秒内失败、然后重启（真凶）

- **现象**：日志每次停在
  ```
  [*] soc: qcom/other; kernel_phys_load=0xa8000000
  [*] init_cred image=… alias=…
  ```
  之后再无输出，永远看不到 `root script written`。
- **根因**：整段 root 脚本是 `snprintf` 的 **format 字符串**（所以原代码里字面百分号写成
  `%%03o`、`%%b`）。我加"存活标记"时写了裸 `%s`，`snprintf` 把 4 个 `%s` 当格式符去吃不存在的
  可变参数 → **在生成脚本这一步直接崩**，根本轮不到内存喷射。
- **修法**：字面 `%` 全部写成 `%%`；并加自检：脚本看起来不对劲时打印
  `root script malformed (n=…)`，这类静默崩溃不会再出现。

---

## 症状 → 处理 速查

| 界面/日志提示 | 含义 | 处理 |
|---|---|---|
| `需要 Shizuku` | Shizuku 没在跑 | 启动 Shizuku（App 内或 adb 拉起 `libshizuku.so`） |
| `Shizuku 权限被拒绝` | 没给本 App 授权 | Shizuku → 应用管理 → 允许「TB320FC 临时 root」 |
| `本次失败：没走到竞态就失败` | exploit 早退 | 看失败摘要里的 `not found`/`denied`；多半是环境或推送问题 |
| `本次失败：进入竞态但没写成` | 竞态输了 | 正常，会自动重启继续 |
| `本次失败：超时（竞态卡住）` | 内核被卡住 | 自动重启继续 |
| `推送 ghostlock-dev 大小不符` | 传输被截断 | 重试；仍失败把日志发出来 |
| `heap spray failed` | 内存泄漏没命中 | 检查 `mm_struct_sz` 是否为 960、`collisions` 是否为 4 |
| `PI route did not produce a verified write` | 路由没写成 | 检查 `waiter_shift` 是否为 −2、`compact_waiter=1` |
| `已经连续尝试 8 次` | 到上限 | 稍后再试；把 `ghostroot.log` 发出来一起看 |

## 日志在哪

- App 内实时显示；
- 落盘：`/sdcard/Android/data/app.tb320fc.ghostroot/files/ghostroot.log`
- root 脚本自己的日志：`/data/local/tmp/.ghostlock_ksu.log`（需要 shell/root 才能读）
