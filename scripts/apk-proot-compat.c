/*
 * apk-proot-compat.so —— Alpine apk-tools v3 在 proot/proroot 容器下的兼容 shim。
 *
 * 背景（Alpine 3.23 起 apk-tools 升 v3，minirootfs 自带 apk 3.0.x）：
 *  1. apk v3 提交包数据库用「匿名临时文件」：openat(dir, O_TMPFILE) 创建匿名
 *     inode，写完后 linkat(AT_FDCWD, "/proc/self/fd/N", dir, tmpname,
 *     AT_SYMLINK_FOLLOW) 把它链入目标位置。proot 的路径翻译对 /proc/self/fd
 *     符号链接处理不正确，linkat 返回 EACCES —— apk 视为致命错误，报
 *     "System state may be inconsistent: failed to write database: Permission
 *     denied" 并中止（apk-src/src/io.c __apk_ostream_to_file / fdo_close）。
 *     apk v3 对 O_TMPFILE 打开失败有优雅降级（命名临时文件 + renameat，proot
 *     完全兼容），降级的触发条件恰是 access("/proc/self/fd") 失败 ——
 *     本 shim 对该路径谎报 ENOENT 即可让 apk v3 永远走安全路径。
 *  2. apk v3 还会用 memfd_create + execve("/proc/self/fd/N") 执行 trigger/
 *     脚本（termux/proot-distro#595、alpinelinux/alpine-make-rootfs#28 均已
 *     确认 proot 下被拒）。返回 ENOSYS 使 apk 降级为文件系统路径执行
 *     （/lib/apk/exec/ 目录，apk-tools 3.0.4+ 行为）。
 *
 * 仅在调用 apk 时通过 LD_PRELOAD 注入（bootstrap.sh 检测 apk-tools 3 后启用；
 * 同时以 /usr/local/bin/apk wrapper 持久化到容器内，终端里手动 apk 同样安全）。
 *
 * 构建（aarch64，产物入 app/src/main/assets/scripts/）：
 *   aarch64-linux-musl-gcc -shared -fPIC -Os -Wall -o apk-proot-compat.so \
 *     apk-proot-compat.c && aarch64-linux-musl-strip apk-proot-compat.so
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

/* 拦 access()/faccessat()：只针对 "/proc/self/fd" 精确匹配（apk3 的
 * is_proc_fd_ok 探测点），其余全部透传，不影响容器内其他程序。 */
static int is_procfd_probe(const char *path) {
        return path != NULL && strcmp(path, "/proc/self/fd") == 0;
}

int access(const char *path, int mode) {
        static int (*real_access)(const char *, int);
        if (is_procfd_probe(path)) {
                errno = ENOENT;
                return -1;
        }
        if (!real_access)
                real_access = (int (*)(const char *, int))dlsym(RTLD_NEXT, "access");
        if (!real_access) {
                errno = ENOSYS;
                return -1;
        }
        return real_access(path, mode);
}

int faccessat(int dirfd, const char *path, int mode, int flags) {
        static int (*real_faccessat)(int, const char *, int, int);
        if (is_procfd_probe(path)) {
                errno = ENOENT;
                return -1;
        }
        if (!real_faccessat)
                real_faccessat = (int (*)(int, const char *, int, int))dlsym(RTLD_NEXT, "faccessat");
        if (!real_faccessat) {
                errno = ENOSYS;
                return -1;
        }
        return real_faccessat(dirfd, path, mode, flags);
}

/* 拦 memfd_create：apk3 以 ENOSYS 为信号降级到文件路径执行脚本。 */
int memfd_create(const char *name, unsigned int flags) {
        (void)name;
        (void)flags;
        errno = ENOSYS;
        return -1;
}
