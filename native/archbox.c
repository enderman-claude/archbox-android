/*
 * archbox: container launcher. Runs as a bionic executable (from nativeLibraryDir),
 * builds user/mount/pid/uts/ipc(/cgroup) namespaces, pivot_roots into the Arch rootfs
 * and execs glibc's dynamic loader directly. After pivot_root there is no bionic
 * linker and no /system anywhere in the container, so glibc is the only libc possible.
 *
 * usage: archbox <rootfs> [program args...]
 * stdin/stdout/stderr must be the pty slave: it becomes /dev/console and /dev/ttyS0.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <sched.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mount.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

#ifndef CLONE_NEWCGROUP
#define CLONE_NEWCGROUP 0x02000000
#endif

#define LD_SO "/usr/lib/ld-linux-aarch64.so.1"

static pid_t child_pid;

static void die(const char *what) {
    fprintf(stderr, "archbox: %s: %s\n", what, strerror(errno));
    _exit(127);
}
static void warn(const char *what) {
    fprintf(stderr, "archbox: warning: %s: %s\n", what, strerror(errno));
}

static void write_file(const char *path, const char *data) {
    int fd = open(path, O_WRONLY);
    if (fd < 0) die(path);
    if (write(fd, data, strlen(data)) < 0) die(path);
    close(fd);
}

static void touch(const char *path) {
    int fd = open(path, O_WRONLY | O_CREAT, 0666);
    if (fd >= 0) close(fd);
}

static void bind_dev(const char *name) {
    char src[64], dst[64];
    snprintf(src, sizeof src, "/dev/%s", name);
    snprintf(dst, sizeof dst, "dev/%s", name);
    touch(dst);
    if (mount(src, dst, NULL, MS_BIND, NULL) < 0) warn(src);
}

static void forward(int sig) { (void)sig; if (child_pid > 0) kill(child_pid, SIGKILL); }

static int run_init(const char *root, char **cmd, const char *tty) {
    if (sethostname("archbox", 7) < 0) warn("sethostname");

    if (mount("none", "/", NULL, MS_REC | MS_PRIVATE, NULL) < 0) die("make / private");
    if (mount(root, root, NULL, MS_BIND | MS_REC, NULL) < 0) die("bind rootfs");
    if (chdir(root) < 0) die("chdir rootfs");

    /* /dev: tmpfs + bind mounts of the few nodes an app is allowed to open */
    mkdir("dev", 0755);
    if (mount("tmpfs", "dev", "tmpfs", MS_NOSUID | MS_STRICTATIME, "mode=755,size=4m") < 0) die("mount /dev");
    static const char *nodes[] = {"null", "zero", "full", "random", "urandom", "tty", NULL};
    for (int i = 0; nodes[i]; i++) bind_dev(nodes[i]);

    /* The serial console: the pty slave we were started on. */
    if (tty) {
        touch("dev/console");
        touch("dev/ttyS0");
        if (mount(tty, "dev/console", NULL, MS_BIND, NULL) < 0) warn("bind console");
        if (mount(tty, "dev/ttyS0", NULL, MS_BIND, NULL) < 0) warn("bind ttyS0");
    }

    mkdir("dev/pts", 0755);
    if (mount("devpts", "dev/pts", "devpts", MS_NOSUID | MS_NOEXEC, "newinstance,ptmxmode=0666,mode=0620") == 0)
        symlink("pts/ptmx", "dev/ptmx");
    else
        warn("mount devpts (no extra ptys)");
    mkdir("dev/shm", 01777);
    mount("tmpfs", "dev/shm", "tmpfs", MS_NOSUID | MS_NODEV, "mode=1777");

    mkdir("proc", 0755);
    if (mount("proc", "proc", "proc", MS_NOSUID | MS_NODEV | MS_NOEXEC, NULL) < 0) warn("mount /proc");
    mkdir("tmp", 01777);
    mount("tmpfs", "tmp", "tmpfs", MS_NOSUID | MS_NODEV, "mode=1777");
    mkdir("run", 0755);
    mount("tmpfs", "run", "tmpfs", MS_NOSUID | MS_NODEV, "mode=755");

    /* pivot_root(".", ".") then lazily drop the old root */
    if (syscall(SYS_pivot_root, ".", ".") < 0) die("pivot_root");
    if (umount2(".", MNT_DETACH) < 0) die("umount old root");
    if (chdir("/") < 0) die("chdir /");

    /* glibc only: the loader must exist inside the rootfs */
    if (access(LD_SO, X_OK) < 0) die("glibc loader " LD_SO " missing (rootfs broken?)");

    pid_t pid = fork();
    if (pid < 0) die("fork");
    if (pid == 0) {
        ioctl(0, TIOCSCTTY, 0);
        const char *t = getenv("TERM");
        char term[64];
        snprintf(term, sizeof term, "%s", t ? t : "xterm-256color");
        clearenv();                       /* no LD_PRELOAD / LD_LIBRARY_PATH from Android */
        setenv("TERM", term, 1);
        setenv("HOME", "/root", 1);
        setenv("USER", "root", 1);
        setenv("LOGNAME", "root", 1);
        setenv("SHELL", "/bin/bash", 1);
        setenv("LANG", "C.UTF-8", 1);
        setenv("PATH", "/usr/local/sbin:/usr/local/bin:/usr/bin", 1);
        /* Android's seccomp filter kills rseq on some versions; glibc tolerates it being off */
        setenv("GLIBC_TUNABLES", "glibc.pthread.rseq=0", 1);
        if (chdir("/root") < 0) chdir("/");

        char *def[] = {"ld-linux-aarch64.so.1", "--library-path", "/usr/lib", "/usr/bin/bash", "--login", NULL};
        if (cmd[0]) {
            int n = 0; while (cmd[n]) n++;
            char **argv = calloc(n + 4, sizeof(char *));
            argv[0] = "ld-linux-aarch64.so.1";
            argv[1] = "--library-path";
            argv[2] = "/usr/lib";
            for (int i = 0; i < n; i++) argv[3 + i] = cmd[i];
            execv(LD_SO, argv);
        } else {
            execv(LD_SO, def);
        }
        die("exec " LD_SO);
    }

    /* PID 1 of the container: reap everything, exit with the shell's status */
    int status = 0, ret = 0;
    for (;;) {
        pid_t w = wait(&status);
        if (w == pid) {
            ret = WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
            kill(-1, SIGKILL);
        }
        if (w < 0 && errno != EINTR) break;
    }
    return ret;
}

int main(int argc, char **argv) {
    if (argc < 2) { fprintf(stderr, "usage: %s <rootfs> [cmd...]\n", argv[0]); return 2; }
    const char *root = argv[1];
    uid_t uid = getuid(); gid_t gid = getgid();
    const char *tty = ttyname(0);
    char ttybuf[128] = "";
    if (tty) { snprintf(ttybuf, sizeof ttybuf, "%s", tty); tty = ttybuf; }

    if (unshare(CLONE_NEWUSER | CLONE_NEWNS | CLONE_NEWUTS | CLONE_NEWIPC | CLONE_NEWPID) < 0) {
        fprintf(stderr, "archbox: unshare(user|mnt|uts|ipc|pid): %s\n", strerror(errno));
        fprintf(stderr, "archbox: this kernel/ROM does not let apps create user namespaces.\n");
        return 126;
    }
    char map[64];
    write_file("/proc/self/setgroups", "deny");
    snprintf(map, sizeof map, "0 %u 1\n", uid);  write_file("/proc/self/uid_map", map);
    snprintf(map, sizeof map, "0 %u 1\n", gid);  write_file("/proc/self/gid_map", map);

    if (unshare(CLONE_NEWCGROUP) < 0) warn("cgroup namespace skipped");
    /* No network namespace on purpose: without CAP_NET_ADMIN on the host there is
       no way to wire one up, and the container would have no internet. */

    child_pid = fork();
    if (child_pid < 0) die("fork");
    if (child_pid == 0) _exit(run_init(root, argv + 2, tty));

    signal(SIGTERM, forward); signal(SIGHUP, forward); signal(SIGINT, forward);
    int status;
    while (waitpid(child_pid, &status, 0) < 0 && errno == EINTR) {}
    return WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
}
