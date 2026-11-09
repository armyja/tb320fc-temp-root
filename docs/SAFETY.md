# 安全边界：能做什么、不能做什么

> 这套工具改的是**运行中的内核数据**，不是磁盘。理解这一点，下面的规则就都能想明白。

## 1. "临时 root"到底存在于哪里

| 载体 | 说明 | 重启后 |
|---|---|---|
| `selinux_state.enforcing`（一个字节） | W1 写 0；root 脚本收尾时再写回 1 | 恢复 |
| 某个子进程的 `task->cred` 指针 | W2 指向内核真实 `init_cred` 的别名 | 进程没了 |
| uid 0 的常驻进程 | `ghostleaf_*` + `root sh` worker | 进程没了 |
| `kernelsu.ko`（LKM） | root 脚本 `ksud late-load` 加载 | 卸载 |
| SELinux policy 的内存修补 | 补回 netlink 标志位 | 恢复 |

**没有任何分区、boot 镜像、vbmeta 被写。** 所以：不会变砖、不会清数据，重启就是干净的原厂运行状态。

## 2. 绝对不要做

### 2.1 不要 kill 承载 root 的进程

`ps -A -o PID,USER,NAME | grep -E 'ghostleaf|sh'` 里那些 uid 0 的进程是 root 的载体。

原因：W2 把 `task->cred` **直接指向内核静态变量 `init_cred`，并且没有增加引用计数**。
这些进程一旦退出，内核会走 `put_cred()` 流程去 `kfree` 它 —— 而 `init_cred` 是内核镜像里的
静态对象，被 `kfree` 之后，任何一次权限检查都可能踩到已释放内存，结果是内核崩溃。

**杀 App 本身没问题**（App 不在那条进程树里），要避免的是显式 `kill` 那些 uid 0 的 worker。

### 2.2 不要 `rmmod kernelsu`

这个 LKM 是 hot-load 进运行内核、替换了若干系统调用入口的，不为卸载设计。强卸会留下悬挂钩子。

### 2.3 不要刷机、不要动 bootloader

`fastboot flash`、`fastboot oem unlock/lock` 与本方案完全无关。bootloader 已锁 + verified boot，
刷写只会失败并可能清数据。想要**永久** root 是另一条路（解 bootloader → 刷 Magisk/KernelSU 镜像），
不要混在一起做。

### 2.4 不要同时用第二个 root 管理器

Magisk / 另一个 KernelSU 变体会和已加载的 `kernelsu.ko` 抢同一套钩子。

### 2.5 不要在"跑的过程中"杀掉 App 或 Shizuku

提权过程中（点按钮后那 10–120 秒）如果 App 或 Shizuku 被杀，exploit 的日志管道会断，
链条中断；可能留下"已经 Permissive、但没拿到 root"的半成品状态，下一次最好先重启。

## 3. 可以放心做

- 用 KernelSU 给 App 授权 su、在终端里跑 root 命令（这就是目的）；
- 正常使用设备：装 App、拍照、看视频；
- 只读检查：`getenforce`、`grep kernelsu /proc/modules`、`ps`、`logcat`；
- **随时重启**：这是最干净的"撤销"，root 消失、一切复原；
- 重新跑一次 App 再拿 root（每次开机都要重来）；
- 卸载 KernelSU、删掉 `/data/local/tmp` 下的工具文件（不影响系统）；
- 导出日志、截图、`adb pull` 文件。

## 4. 失败会发生什么

| 现象 | 原因 | 后果 |
|---|---|---|
| 卡住无输出 | 竞态把内核状态弄脏（RCU stall / 死锁） | 内核半死，需要重启；数据无影响 |
| 设备自己重启 | 内核 panic（cmdline 里有 `panic_on_rcu_stall=1`） | 正常重启，数据无影响 |
| App 报"失败" | 这次竞态没赢（约 2/3 概率） | 重试即可 |

**不会**：变砖、丢数据、影响保修状态之外的持久改动（因为没写分区）。

> 副作用提醒：root 脚本会把 `panic_on_rcu_stall/panic_on_oops/panic_on_warn/hung_task_panic`
> 临时置 0，让**后续**失败倾向于"卡住"而不是重启（降低重试成本）。这也意味着卡住时不会自动恢复，
> 需要你手动重启。重启后这些 sysctl 回到默认值。

## 5. 使用范围

- **只在你自己的设备上用。** 不要用在他人设备、公司资产、租借设备上。
- 这是研究/复现性质的临时提权，不提供任何担保；因使用导致的任何后果由使用者承担。
- 如果你要把它做成产品、或用于批量设备，请先自行评估法律与合规风险。
