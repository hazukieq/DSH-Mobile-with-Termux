# dsh-android PTY 原生层
cmake_minimum_required(VERSION 3.22.1)
project(dshpty C)

add_library(dshpty SHARED dsh_pty.c)

# 16KB 页对齐：Android 15+ 部分 aarch64 真机内核使用 16384 字节页，
# LOAD p_align=4096 的 so 在其上 dlopen 会报 "program alignment" 失败。
# 不依赖 NDK r27+ 的默认行为，对所有 ndkVersion 显式生效。
target_link_options(dshpty PRIVATE
    "-Wl,-z,max-page-size=16384")

target_link_libraries(dshpty
    # 仅依赖 log，无 STL —— 纯 C11 实现，规避 NDK STL 兼容问题
    log)

# 路径映射预载层（2026-11-20）：把 /tmp、/var/tmp、/dev/shm、/run 透明重定向到 $DSH_TMPDIR。
# 只作为 LD_PRELOAD 使用，App 自身不 loadLibrary 它。
add_library(dshpathmap SHARED dsh_pathmap.c)

# NDK r24+ 默认开 _FORTIFY_SOURCE：它会用 __pass_object_size 重声明 open/openat，
# 与本文件的拦截原型冲突（编译期直接报 redeclaration）。本库只做符号转发，
# 不需要 fortify 的边界检查，显式关掉。
target_compile_options(dshpathmap PRIVATE -U_FORTIFY_SOURCE -D_FORTIFY_SOURCE=0)

target_link_options(dshpathmap PRIVATE
    "-Wl,-z,max-page-size=16384")

target_link_libraries(dshpathmap
    # dl 在 bionic 里并进 libc，但显式声明依赖更稳
    dl)
