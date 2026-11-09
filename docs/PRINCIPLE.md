# 原理：这台平板是怎么被临时拿到 root 的

本文只讲**复现原理与本机适配**，不等同于一份可直接复用的通用 exploit：
真正的利用核心来自 [GhostLock](https://github.com/YuKongA/ghostlock-app)，本仓库提供的是
**设备适配（参数、路由选择、一键封装）** 与踩坑记录。

> 目的说明：写这一节是为了让适配过程可审计、可复核，而不是提供新的攻击能力。

---

## 1. 漏洞本体：CVE-2026-43499

Linux 内核 `rt_mutex`（PI-futex 的底层）在 `remove_waiter()` 里存在一处 use-after-free：
在特定竞态下，内核会在"等待者已经从链上摘除"之后仍然去访问它的字段（`waiter->task`、
`waiter->lock` 等）。修补后的实现不再依赖 `current`，而旧实现会。

**本机是否受影响**：反汇编 `remove_waiter`（kallsyms 给出 `0xffffffc0081ea798`），
函数里仍然有 `mrs SP_EL0`（取 `current`）以及对 `current->pi_lock` / `current->pi_blocked_on`
的读写 —— 说明**未修补**，原语存在。

这也是这类漏洞的通用判定法：不看内核版本号猜，直接把目标函数反汇编出来看它是否还碰 `current`。

---

## 2. 从 UAF 到"往指定地址写一个值"

GhostLock 的思路可以概括成三步：

1. **伪造一个 `rt_mutex_waiter`**：它的 `tree_entry` / `pi_tree_entry`（红黑树节点）、`task`、`lock`、
   `prio` 等字段全部由我们控制（值来自用户态可控的一块内核内存，见下一步）。
2. **让内核在 PI 链遍历时把它当成真的等待者**：`futex(FUTEX_CMP_REQUEUE_PI)` 把 waiter 挂进
   目标 futex 的 PI 树，内核沿树查找并"重新链接"节点。因为漏洞，它会在我们指定的
   `tree_left`（= 目标地址）上做一次写入，写入的值就是 `tree_entry.__rb_parent_color`（= 我们给的值）。
3. **把"用户态可控的内存"放到内核栈上**：这是关键的一步 —— 三条 route 都靠"让内核把用户数据
   拷到栈上的某个位置，再让 futex 的 waiter 落在这块栈空间里"。

本机采用的 route 是 **`select_stack`**：`pselect(320, …)` 会把 3 个 fd_set（每个 40 字节）
从用户态拷到 `core_sys_select` 的栈缓冲里；而 `futex_wait_requeue_pi` 的 waiter 也在这段栈上，
两者的相对位置由**栈帧大小**唯一决定 —— 这就是 `waiter_shift` 的来历（见 [PARAMS.md](PARAMS.md)）。

另外两条 route 在本机不可用：

- `multicast_waiter`：`waiter_off = 336 > buffer_size = 264`，waiter 落在多播缓冲区外，
  结构上不成立；
- `tcp_zerocopy`：路径存在，但没有为本代内核标定布局，未采用。

> 为什么"栈帧算出来就能用"：这些 syscall 链在同一内核上是固定的，
> 帧大小 + 局部变量偏移可以从 `boot.img` 反汇编里精确量出来，不需要跑设备。

---

## 3. 提权链：W1 → W2 → handoff

拿到"往任意地址写一个可控值"的能力之后，**不做** shellcode/函数指针劫持（本机有 CFI），
只改数据：

### W1：关掉 SELinux

- 目标：`selinux_state.enforcing`（本机偏移 0，值为 0）。
- 写法：`mode=Zero`（写 0）。
- 效果：短暂变成 Permissive，后续步骤不再被 SELinux 拦。
- 验证：`getenforce` → `Permissive`（`attack::check_selinux_off()`）。

### W2：把某个进程的凭据换成 root 的

- 目标：被提权子进程的 `task_struct.cred`（本机 `task+0x780`）。
- 写的值：内核里真实 `init_cred` 的**直接映射别名**（本机 `0xffffff802a7c0ae0`）。
- 效果：那个子进程立刻 `getuid() == 0`。
- 验证：子进程通过管道回报 uid，父进程看到 `child uid = 0` 才继续。

> 这一步对物理地址很敏感：别名 = `PAGE_OFFSET + (phys_load − phys_offset) + image偏移`。
> 所以 **W1/W2 成功本身就是"物理地址两项都对"的证明**。

### handoff：用 uid 0 去做真正有用的事

root 子进程以 uid 0 执行 `/data/local/tmp/.ghostlock_root.sh`：

1. 读 `/proc/iomem`（只有 root 能读）缓存下来；
2. 修 SELinux policy 里被裁掉的 netlink 标志位（让后续 SELinux 操作正常）；
3. 如果系统里有 `ksud`（KernelSU / ReSukiSU / BakaSU / KowSU），执行
   `ksud late-load --kmi android12-5.10` 加载 `kernelsu.ko`；
4. 最后把 SELinux 恢复成 Enforcing。

于是：**root 由"常驻的 uid 0 进程 + 已加载的内核模块"承载，全部在内存里，重启即消失。**

---

## 4. 为什么整条链需要"竞态"，以及为什么失败会重启

栈窗口是有寿命的：`pselect` 拷贝 fd_set → 内核进 PI 链 → 写 → 返回。写入必须发生在
"waiter 已经挂上、栈数据还没被覆盖"的窗口里，早一点晚一点都会：

- 写不进去（这次尝试白费，可以重试）；
- 或者内核遍历到半成品树 → `panic`（本机 cmdline 里有 `kernel.panic_on_rcu_stall=1`，会直接重启）。

所以：**成功率天然不高（约 1/4），失败可能导致重启**。这是这个漏洞的性质，不是配置错误。

---

## 5. 本机适配做了什么（相对上游的改动）

| 改动 | 原因 |
|---|---|
| 编译期镜像基址 `0xffffffc080000000` → `0xffffffc008000000` | 上游是 6.x 布局，本机 `_head = 0xffffffc008000000` |
| 设备参数（结构体偏移、路由几何、phys、凭据引用） | 见 [PARAMS.md](PARAMS.md) |
| `mm_struct_sz = 960` | `sizeof(struct mm_struct)`，从 `mm_cache_init` 的 `mov w1, #0x3c0` 读出 |
| `select_stack.waiter_shift = -2` | 应用内部 waiter word 编号比结构体下标大 2 |
| victim 管道 fd 抬到 400 以上 | select 路由 `destroy()` 会关 fd 3..319，adb shell 下会误杀管道 |
| `setvbuf(stdout, _IOLBF)` | 日志重定向到文件时块缓冲，进程被 kill 会丢尾部，误判"卡死点" |
| root 脚本里加 4 个 sysctl 归零 | 让后续失败倾向于"卡住"而不是重启（降损） |
| root 脚本写 `.ghostlock_status`（boot_id） | 给 App 判断"这次开机是否已 root" |

完整 patch：`native/patches/ghostlock-5.10.209-tb320fc.patch`。

---

## 6. 这个方案的边界

- 不能持久（重启失效），因为它只改内存；
- 不能绕过 bootloader 锁（也不需要）——它不写任何分区；
- 不是"通用 root"：换内核就全废，必须重新提取参数；
- 有概率 panic 重启，不适合对稳定性敏感的场景。
