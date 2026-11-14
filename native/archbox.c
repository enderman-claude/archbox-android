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
#include <sys/utsname.h>
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
#include <sys/prctl.h>
#include <poll.h>
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

        char *def[] = {"ld-linux-aarch64.so.1", "--library-path", "/usr/lib", "/usr/bin/bash", "--login", "-i", NULL};
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

/* Report which namespace types this kernel lets an app create (each probed in a throwaway child). */
static void diagnose(void) {
    struct { const char *name; int flag; } t[] = {
        {"user", CLONE_NEWUSER}, {"mnt", CLONE_NEWNS}, {"uts", CLONE_NEWUTS},
        {"ipc", CLONE_NEWIPC}, {"pid", CLONE_NEWPID}, {"cgroup", CLONE_NEWCGROUP},
    };
    struct utsname u;
    if (uname(&u) == 0) fprintf(stderr, "archbox: kernel %s\n", u.release);
    for (unsigned i = 0; i < sizeof t / sizeof t[0]; i++) {
        pid_t p = fork();
        if (p == 0) _exit(unshare(t[i].flag) < 0 ? errno : 0);
        int st = 0; waitpid(p, &st, 0);
        int e = WIFEXITED(st) ? WEXITSTATUS(st) : -1;
        fprintf(stderr, "archbox:   %-6s %s\n", t[i].name, e == 0 ? "ok" : e > 0 ? strerror(e) : "killed (seccomp?)");
    }
    char buf[32] = "";
    FILE *f = fopen("/proc/sys/user/max_user_namespaces", "r");
    if (f) { if (fgets(buf, sizeof buf, f)) fprintf(stderr, "archbox: max_user_namespaces=%s", buf); fclose(f); }
    else fprintf(stderr, "archbox: /proc/sys/user/max_user_namespaces missing (CONFIG_USER_NS off?)\n");
    fprintf(stderr, "archbox: this kernel/ROM does not let apps create the namespaces above.\n");
}

/* Can this app create the namespaces the container needs? Probed in a throwaway child so a
   refusal (or a seccomp kill) leaves this process untouched. Returns 0 if ok, else the errno
   (or -1 if the child was killed). */
static int probe_namespaces(void) {
    pid_t p = fork();
    if (p < 0) return errno;
    if (p == 0) {
        if (unshare(CLONE_NEWUSER) < 0) _exit(errno);
        if (unshare(CLONE_NEWNS) < 0) _exit(errno);
        if (unshare(CLONE_NEWPID) < 0) _exit(errno);
        _exit(0);
    }
    int st = 0;
    while (waitpid(p, &st, 0) < 0 && errno == EINTR) {}
    return WIFEXITED(st) ? WEXITSTATUS(st) : -1;
}

/* ---- ptrace self-test, run when proot fails: prints why syscall rewriting may not work ---- */
#include <sys/ptrace.h>
#include <sys/prctl.h>
#include <sys/uio.h>
#include <elf.h>
#include <stdint.h>
#include <time.h>
#include <malloc.h>
#ifndef M_BIONIC_SET_HEAP_TAGGING_LEVEL
#define M_BIONIC_SET_HEAP_TAGGING_LEVEL (-204)
#endif

#ifndef NT_PRSTATUS
#define NT_PRSTATUS 1
#endif
#ifndef PTRACE_O_TRACESYSGOOD
#define PTRACE_O_TRACESYSGOOD 1
#endif
#ifndef NT_ARM_SYSTEM_CALL
#define NT_ARM_SYSTEM_CALL 0x404
#endif
#ifndef PR_GET_TAGGED_ADDR_CTRL
#define PR_GET_TAGGED_ADDR_CTRL 56
#endif

static void status_line(const char *key) {
    FILE *f = fopen("/proc/self/status", "r");
    char line[256];
    if (!f) return;
    while (fgets(line, sizeof line, f)) {
        if (!strncmp(line, key, strlen(key))) {
            line[strcspn(line, "\n")] = 0;
            fprintf(stderr, "  %s\n", line);
        }
    }
    fclose(f);
}

static void ptrace_probe(void) {
    fprintf(stderr, "archbox probe (why proot failed):\n");
    status_line("Seccomp");
    long tag = prctl(PR_GET_TAGGED_ADDR_CTRL, 0, 0, 0, 0);
    fprintf(stderr, "  tagged_addr_ctrl=%ld\n", tag);

    char *heap = malloc(32);
    strcpy(heap, "probe");
    volatile long stackv = 0x1234;
    int mo = mallopt(M_BIONIC_SET_HEAP_TAGGING_LEVEL, 0);   /* heap tagging off from here on */
    char *heap2 = malloc(32);
    strcpy(heap2, "probe");
    errno = 0;
    pid_t c = fork();
    if (c < 0) { fprintf(stderr, "  fork: %s\n", strerror(errno)); return; }
    if (c == 0) {
        if (ptrace(PTRACE_TRACEME, 0, 0, 0) < 0) _exit(100 + (errno & 63));
        raise(SIGSTOP);
        errno = 0;
        long r = syscall(SYS_getppid);
        _exit(r == 4242 ? 0 : (errno ? errno : 99));
    }
    int st = 0;
    waitpid(c, &st, 0);
    if (!WIFSTOPPED(st)) {
        fprintf(stderr, "  child died before stop (%s %d)\n", WIFSIGNALED(st) ? "sig" : "exit",
                WIFSIGNALED(st) ? WTERMSIG(st) : WEXITSTATUS(st));
        return;
    }
    errno = 0;
    long w = ptrace(PTRACE_PEEKDATA, c, heap, 0);
    fprintf(stderr, "  peek heap %p: %s\n", (void *)heap, errno ? strerror(errno) : "ok");
    errno = 0;
    w = ptrace(PTRACE_PEEKDATA, c, heap2, 0);
    fprintf(stderr, "  mallopt(notag)=%d, new heap %p: %s\n", mo, (void *)heap2, errno ? strerror(errno) : "ok");
    errno = 0;
    w = ptrace(PTRACE_PEEKDATA, c, (void *)&stackv, 0); (void)w;
    fprintf(stderr, "  peek stack %p: %s\n", (void *)&stackv, errno ? strerror(errno) : "ok");

    ptrace(PTRACE_SETOPTIONS, c, 0, (void *)(long)PTRACE_O_TRACESYSGOOD);
    int inside = 0, got_nr = 0, void_rc = -2, res_rc = -2, void_errno = 0, res_errno = 0, sig = 0;
    for (int guard = 0; guard < 200; guard++) {
        if (ptrace(PTRACE_SYSCALL, c, 0, (void *)(long)sig) < 0) { fprintf(stderr, "  PTRACE_SYSCALL: %s\n", strerror(errno)); break; }
        sig = 0;
        waitpid(c, &st, 0);
        if (WIFEXITED(st) || WIFSIGNALED(st)) break;
        if (!WIFSTOPPED(st)) break;
        int ss = WSTOPSIG(st);
        if (ss != (SIGTRAP | 0x80)) { if (ss != SIGSTOP && ss != SIGTRAP) sig = ss; continue; }
#if defined(__aarch64__)
        uint64_t regs[34];
        struct iovec iov = { regs, sizeof regs };
        if (ptrace(PTRACE_GETREGSET, c, (void *)NT_PRSTATUS, &iov) < 0) { fprintf(stderr, "  GETREGSET: %s\n", strerror(errno)); break; }
        long nr = (long)regs[8];
#else
        struct { unsigned long long r[27]; } regs; /* user_regs_struct */
        struct iovec iov = { &regs, sizeof regs };
        if (ptrace(PTRACE_GETREGSET, c, (void *)NT_PRSTATUS, &iov) < 0) { fprintf(stderr, "  GETREGSET: %s\n", strerror(errno)); break; }
        long nr = (long)regs.r[15]; /* orig_rax */
#endif
        if (!inside) {
            inside = 1;
            if (nr == SYS_getppid && !got_nr) {
                got_nr = 1;
#if defined(__aarch64__)
                int v = -1;
                struct iovec vi = { &v, sizeof v };
                errno = 0; void_rc = (int)ptrace(PTRACE_SETREGSET, c, (void *)NT_ARM_SYSTEM_CALL, &vi);
#else
                regs.r[15] = (unsigned long long)-1;
                errno = 0; void_rc = (int)ptrace(PTRACE_SETREGSET, c, (void *)NT_PRSTATUS, &iov);
#endif
                void_errno = errno;
            }
        } else {
            inside = 0;
            if (got_nr && res_rc == -2) {
#if defined(__aarch64__)
                regs[0] = 4242;
#else
                regs.r[10] = 4242; /* rax */
#endif
                errno = 0; res_rc = (int)ptrace(PTRACE_SETREGSET, c, (void *)NT_PRSTATUS, &iov);
                res_errno = errno;
            }
        }
    }
    ptrace(PTRACE_CONT, c, 0, 0);
    waitpid(c, &st, 0);
    fprintf(stderr, "  void syscall: %s%s\n", void_rc == 0 ? "ok" : "FAILED ", void_rc == 0 ? "" : strerror(void_errno));
    fprintf(stderr, "  set result:   %s%s\n", res_rc == 0 ? "ok" : "FAILED ", res_rc == 0 ? "" : strerror(res_errno));
    if (WIFEXITED(st)) {
        int e = WEXITSTATUS(st);
        fprintf(stderr, "  tracee saw:   %s", e == 0 ? "42 (rewrite works)" : e >= 100 && e < 164 ? "TRACEME failed" : strerror(e));
        if (e >= 100 && e < 164) fprintf(stderr, " (%s)", strerror(e - 100));
        fprintf(stderr, "\n");
    } else if (WIFSIGNALED(st)) {
        fprintf(stderr, "  tracee killed by signal %d\n", WTERMSIG(st));
    }
}

/* Fallback for kernels without user namespaces: PRoot (ptrace based) shipped as libproot*.so
   next to this binary. Never returns on success. */
static void exe_dir(char *dir, size_t len) {
    ssize_t n = readlink("/proc/self/exe", dir, len - 1);
    if (n <= 0) die("readlink /proc/self/exe");
    dir[n] = 0;
    char *slash = strrchr(dir, '/');
    if (slash) *slash = 0;
}

/* probe != 0: quiet health check, no ptrace self-test output (see proot_works) */
static int run_proot(const char *root, char **cmd, const char *tty, int probe) {
    char dir[512];
    exe_dir(dir, sizeof dir);

    /* targetSdk >= 29 forbids execve() of files in app data: "userland" PRoot maps ELF files itself */
    const char *ul = getenv("ARCHBOX_USERLAND");
    int userland = ul && ul[0] == '1';
    char proot[640], loader[640], tmp[640];
    snprintf(proot, sizeof proot, "%s/%s", dir, userland ? "libproot-userland.so" : "libproot.so");
    snprintf(loader, sizeof loader, "%s/libproot-loader.so", dir);
    const char *t = getenv("ARCHBOX_TMP");
    if (t && *t) snprintf(tmp, sizeof tmp, "%s", t);
    else snprintf(tmp, sizeof tmp, "%s/../proot-tmp", root);
    mkdir(tmp, 0700);
    if (access(proot, X_OK) < 0) die(proot);
    if (access(loader, X_OK) < 0) die(loader);

    const char *term = getenv("TERM");
    char termbuf[64];
    snprintf(termbuf, sizeof termbuf, "%s", term ? term : "xterm-256color");
    /* PRoot's seccomp acceleration makes syscalls fail with ENOSYS on some Android kernels
       ("execve: Function not implemented"), so it is off unless ARCHBOX_PROOT_SECCOMP=1. */
    const char *sc = getenv("ARCHBOX_PROOT_SECCOMP");
    int use_seccomp = sc && sc[0] == '1';
    clearenv();                          /* no LD_PRELOAD / LD_LIBRARY_PATH from Android */
    setenv("TERM", termbuf, 1);
    setenv("HOME", "/root", 1);
    setenv("USER", "root", 1);
    setenv("LOGNAME", "root", 1);
    setenv("SHELL", "/bin/bash", 1);
    setenv("LANG", "C.UTF-8", 1);
    setenv("PATH", "/usr/local/sbin:/usr/local/bin:/usr/bin", 1);
    setenv("GLIBC_TUNABLES", "glibc.pthread.rseq=0", 1);
    setenv("PROOT_TMP_DIR", tmp, 1);
    setenv("PROOT_LOADER", loader, 1);
    if (!use_seccomp) setenv("PROOT_NO_SECCOMP", "1", 1);
    /* Without seccomp acceleration PRoot never detects that this kernel runs the app's seccomp
       filter *after* the ptrace syscall-enter stop. Every syscall PRoot voids to return an error
       (ENOENT/EEXIST...) then hits Android's filter as syscall -1 and the tracee sees ENOSYS /
       SIGSYS. Tell it up front so it suppresses that SIGSYS and restores the result. */
    setenv("PROOT_ASSUME_NEW_SECCOMP", "1", 1);

    char *argv[64];
    int a = 0;
    char console[160] = "", ttys0[160] = "";
    argv[a++] = proot;
    argv[a++] = "--kill-on-exit";
    argv[a++] = "--link2symlink";
    argv[a++] = "-0";
    argv[a++] = "-r"; argv[a++] = (char *)root;
    argv[a++] = "-b"; argv[a++] = "/dev";
    argv[a++] = "-b"; argv[a++] = "/proc";
    argv[a++] = "-b"; argv[a++] = "/sys";
    argv[a++] = "-b"; argv[a++] = "/proc/self/fd:/dev/fd";
    argv[a++] = "-b"; argv[a++] = "/proc/self/fd/0:/dev/stdin";
    argv[a++] = "-b"; argv[a++] = "/proc/self/fd/1:/dev/stdout";
    argv[a++] = "-b"; argv[a++] = "/proc/self/fd/2:/dev/stderr";
    if (tty) {
        snprintf(console, sizeof console, "%s:/dev/console", tty);
        snprintf(ttys0, sizeof ttys0, "%s:/dev/ttyS0", tty);
        argv[a++] = "-b"; argv[a++] = console;
        argv[a++] = "-b"; argv[a++] = ttys0;
    }
    argv[a++] = "-w"; argv[a++] = "/root";
    argv[a++] = LD_SO;
    argv[a++] = "--library-path";
    argv[a++] = "/usr/lib";
    if (cmd[0]) { for (int i = 0; cmd[i] && a < 62; i++) argv[a++] = cmd[i]; }
    else { argv[a++] = "/usr/bin/bash"; argv[a++] = "--login"; argv[a++] = "-i"; }
    argv[a] = NULL;
    setenv("MEMTAG_OPTIONS", "off", 1);   /* no heap pointer tags: ptrace can't read tagged addresses */
    time_t t0 = time(NULL);
    child_pid = fork();
    if (child_pid < 0) die("fork");
    if (child_pid == 0) { execv(proot, argv); fprintf(stderr, "archbox: exec %s: %s\n", proot, strerror(errno)); _exit(127); }
    signal(SIGTERM, forward); signal(SIGHUP, forward); signal(SIGINT, forward);
    int status = 0;
    while (waitpid(child_pid, &status, 0) < 0 && errno == EINTR) {}
    int code = WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
    if (!probe && code != 0 && time(NULL) - t0 < 8) {
        fprintf(stderr, "\narchbox: proot exited with %d right after start\n", code);
        ptrace_probe();
    }
    return code;
}


/* ---- tier 2 health check -------------------------------------------------------------------
   PRoot "starting" proves nothing: on the broken kernels it starts fine and then every
   translated syscall fails with ENOSYS. So run a real filesystem operation through it first. */
static int proot_works(const char *root, const char *tty) {
    static char *probe_cmd[] = {"/usr/bin/mkdir", "-p", "/tmp/.archbox-probe", NULL};
    pid_t p = fork();
    if (p < 0) return 0;
    if (p == 0) {
        setpgid(0, 0);
        _exit(run_proot(root, probe_cmd, tty, 1));
    }
    int st = 0;
    for (int i = 0; i < 100; i++) {                       /* 10 s */
        pid_t w = waitpid(p, &st, WNOHANG);
        if (w == p) return WIFEXITED(st) && WEXITSTATUS(st) == 0;
        if (w < 0 && errno != EINTR) return 0;
        usleep(100000);
    }
    kill(-p, SIGKILL);                                    /* hung: take proot and tracees down */
    waitpid(p, &st, 0);
    return 0;
}

/* ---- tier 3: fakechroot + fakeroot -----------------------------------------------------------
   No namespaces, no ptrace: glibc's own dynamic loader runs the rootfs binaries with
   libfakechroot.so (rewrites path arguments of libc calls to <root>/path) and libfakeroot.so
   (fake uid 0 / chown via the `faked` daemon) preloaded. All three are glibc objects shipped as
   lib*.so in nativeLibraryDir.
   Deliberately NOT `fakechroot fakeroot chroot <root> /bin/bash`: the first two are shell
   scripts, and a "#!/bin/sh" line cannot resolve on Android. Setting the environment those
   scripts would set, and exec'ing the loader ourselves, is equivalent and needs no host shell.
   Limits: static binaries and raw syscalls bypass the preload; needs exec from app data
   (targetSdk 28 build only, same as non-userland proot). */
static pid_t faked_pid;
static void kill_faked(void) { if (faked_pid > 0) { kill(faked_pid, SIGKILL); faked_pid = 0; } }
static void forward_fc(int sig) { (void)sig; if (child_pid > 0) kill(child_pid, SIGKILL); kill_faked(); }

/* Start the bundled faked daemon (tcp flavour: SysV IPC is blocked on Android) through the
   rootfs loader. It prints "<key>:<pid>"; the key goes into FAKEROOTKEY. Returns 0 on success. */
static int start_faked(const char *dir, const char *root, const char *ld, const char *libpath, char *key, size_t keylen) {
    char faked[640];
    snprintf(faked, sizeof faked, "%s/libfaked.so", dir);
    if (access(faked, R_OK) < 0) { warn(faked); return -1; }
    int fds[2];
    if (pipe(fds) < 0) return -1;
    faked_pid = fork();
    if (faked_pid < 0) return -1;
    if (faked_pid == 0) {
        prctl(PR_SET_PDEATHSIG, SIGKILL);                 /* never outlive the launcher */
        dup2(fds[1], 1); close(fds[0]); close(fds[1]);
        char *argv[] = {"ld-linux-aarch64.so.1", "--library-path", (char *)libpath, faked, "--foreground", NULL};
        setenv("GLIBC_TUNABLES", "glibc.pthread.rseq=0", 1);
        execv(ld, argv);
        _exit(127);
    }
    close(fds[1]);
    struct pollfd pf = {fds[0], POLLIN, 0};
    char buf[64] = "";
    if (poll(&pf, 1, 5000) > 0) { ssize_t n = read(fds[0], buf, sizeof buf - 1); if (n > 0) buf[n] = 0; }
    close(fds[0]);
    char *colon = strchr(buf, ':');
    if (!colon) {
        fprintf(stderr, "archbox debug [faked] no key from daemon (got %zu bytes: \"%s\")\n", strlen(buf), buf);
        kill_faked();
        return -1;
    }
    *colon = 0;
    snprintf(key, keylen, "%s", buf);
    (void)root;
    return 0;
}

/* Debug: which syscalls does Android's filter block for this app? Each candidate runs in its own
   forked child with arguments chosen to fail harmlessly (NULL pointers, invalid flags, -1 fds), so
   nothing takes effect if the filter lets the call through. A child that dies of SIGSYS means the
   filter blocked it. Printed to the terminal, so no adb is needed. */
#include <sys/syscall.h>
struct sc_probe { const char *name; long nr; long a, b, c, d; };
#define SCP(name, a1, a2, a3, a4) { #name, SYS_##name, (long)(a1), (long)(a2), (long)(a3), (long)(a4) }

/* ---- debug layer (beta 9): process, file, environment and live-syscall tracing ----------- */
#include <dirent.h>
#include <pthread.h>

static void dbg_file(const char *label, const char *path) {
    struct stat st;
    if (stat(path, &st) < 0) {
        fprintf(stderr, "archbox debug [%s] %s: stat failed: %s\n", label, path, strerror(errno));
        return;
    }
    unsigned char hdr[4] = {0};
    int fd = open(path, O_RDONLY);
    if (fd >= 0) { if (read(fd, hdr, 4) < 0) {} close(fd); }
    fprintf(stderr,
            "archbox debug [%s] %s: size=%lld mode=%o uid=%d gid=%d header=%02x%02x%02x%02x x=%d r=%d\n",
            label, path, (long long)st.st_size, (unsigned)(st.st_mode & 07777), (int)st.st_uid,
            (int)st.st_gid, hdr[0], hdr[1], hdr[2], hdr[3], access(path, X_OK) == 0, access(path, R_OK) == 0);
}

static void dbg_proc(const char *who, const char *path) {
    FILE *f = fopen(path, "r");
    if (!f) { fprintf(stderr, "archbox debug [%s] %s: %s\n", who, path, strerror(errno)); return; }
    char line[256];
    while (fgets(line, sizeof line, f)) {
        line[strcspn(line, "\n")] = 0;
        fprintf(stderr, "archbox debug [%s] %s\n", who, line);
    }
    fclose(f);
}

static void dbg_startup(const char *who) {
    struct utsname u;
    if (uname(&u) == 0)
        fprintf(stderr, "archbox debug [%s] uname %s %s %s %s\n", who, u.sysname, u.release, u.version, u.machine);
    dbg_proc(who, "/proc/self/status");
    dbg_proc(who, "/proc/self/limits");
    dbg_proc(who, "/proc/self/attr/current");
    fprintf(stderr, "archbox debug [%s] pid=%d ppid=%d pgid=%d sid=%d\n", who,
            (int)getpid(), (int)getppid(), (int)getpgrp(), (int)getsid(0));
    for (int fd = 0; fd < 16; fd++) {
        char lnk[64], tgt[256];
        snprintf(lnk, sizeof lnk, "/proc/self/fd/%d", fd);
        ssize_t n = readlink(lnk, tgt, sizeof tgt - 1);
        if (n > 0) { tgt[n] = 0; fprintf(stderr, "archbox debug [%s] fd %d -> %s\n", who, fd, tgt); }
    }
}

static void dbg_env(const char *who) {
    extern char **environ;
    for (char **e = environ; e && *e; e++) {
        char tmp[200];
        snprintf(tmp, sizeof tmp, "%s", *e);
        fprintf(stderr, "archbox debug [%s] env %s\n", who, tmp);
    }
}

/* Samples /proc/<pid>/syscall while the guest runs and counts the syscall numbers it sees. */
#define MON_MAX 512
static pid_t mon_pid;
static volatile int mon_run;
static long mon_count[MON_MAX];
static long mon_running, mon_none;

static void *mon_thread(void *arg) {
    (void)arg;
    char path[64];
    snprintf(path, sizeof path, "/proc/%d/syscall", (int)mon_pid);
    while (mon_run) {
        int fd = open(path, O_RDONLY);
        if (fd >= 0) {
            char buf[256] = {0};
            ssize_t n = read(fd, buf, sizeof buf - 1);
            close(fd);
            if (n > 0) {
                if (!strncmp(buf, "running", 7)) mon_running++;
                else {
                    long nr = strtol(buf, NULL, 10);
                    if (nr >= 0 && nr < MON_MAX) mon_count[nr]++;
                    else mon_none++;
                }
            }
        }
        usleep(300);
    }
    return NULL;
}

static void mon_report(void) {
    fprintf(stderr, "archbox debug [guest syscalls] samples: running=%ld none=%ld\n", mon_running, mon_none);
    for (int i = 0; i < MON_MAX; i++)
        if (mon_count[i]) fprintf(stderr, "archbox debug [guest syscalls] nr=%d seen=%ld\n", i, mon_count[i]);
}

static void scan_syscalls(void) {
    struct sc_probe list[] = {
        SCP(openat, -100, 0, 0, 0),          /* AT_FDCWD, NULL path: EFAULT */
        SCP(clone3, 0, 0, 0, 0),             /* NULL args: EFAULT */
        SCP(faccessat2, -100, 0, 0, 0),
        SCP(socket, 9999, 0, 0, 0),          /* bogus domain: EAFNOSUPPORT */
        SCP(bind, -1, 0, 0, 0),              /* bad fd: EBADF */
        SCP(connect, -1, 0, 0, 0),
        SCP(personality, 0xffffffffL, 0, 0, 0), /* query only */
        SCP(prctl, -1, 0, 0, 0),             /* bad option: EINVAL */
        SCP(ptrace, -1, 0, 0, 0),            /* bad request: EIO */
        SCP(bpf, -1, 0, 0, 0),               /* bad cmd: EINVAL */
        SCP(mount, 0, 0, 0, 0),              /* NULL: EFAULT */
        SCP(unshare, 0, 0, 0, 0),            /* no flags: no-op */
        SCP(setresuid, -1, -1, -1, 0),       /* -1 = leave unchanged */
        SCP(setresgid, -1, -1, -1, 0),
        SCP(setgroups, -1, 0, 0, 0),         /* bad count: EINVAL */
        SCP(chroot, 0, 0, 0, 0),             /* NULL: EFAULT */
        SCP(pivot_root, 0, 0, 0, 0),         /* NULL: EFAULT */
        SCP(mknodat, -100, 0, 0, 0),         /* NULL: EFAULT */
        SCP(fchownat, -1, 0, -1, -1),        /* bad fd, NULL path */
        SCP(rseq, 0, 0, 0, 0),               /* NULL: EFAULT */
        SCP(membarrier, -1, 0, 0, 0),        /* bad cmd: EINVAL */
        SCP(userfaultfd, -1, 0, 0, 0),       /* bad flags: EINVAL */
        SCP(futex, 0, 0, 0, 0),              /* NULL uaddr: EFAULT */
        SCP(getrandom, 0, 0, 0, 0),          /* NULL buf: EFAULT */
        SCP(mprotect, 0, 0, 0, 0),           /* zero length: no-op */
        SCP(madvise, 0, 0, 0, 0),            /* zero length: no-op */
        SCP(prctl, -1, 0, 0, 0),             /* bad option: EINVAL */
        SCP(sigaltstack, 0, 0, 0, 0),        /* query only */
        SCP(rt_sigprocmask, -1, 0, 0, 0),    /* bad how: EINVAL */
        SCP(close_range, -1, -1, 0, 0),      /* range -1..-1: EINVAL */
        SCP(statx, -100, 0, 0, 0),           /* NULL path: EFAULT */
        SCP(readlinkat, -100, 0, 0, 0),      /* NULL path: EFAULT */
        SCP(newfstatat, -100, 0, 0, 0),      /* NULL path: EFAULT */
        SCP(getdents64, -1, 0, 0, 0),        /* bad fd: EBADF */
        SCP(pipe2, 0, 0, 0, 0),              /* NULL: EFAULT */
        SCP(dup3, -1, -1, 0, 0),             /* bad fd: EBADF */
        SCP(epoll_create1, -1, 0, 0, 0),     /* bad flags: EINVAL */
        SCP(memfd_create, 0, 0, 0, 0),       /* NULL name: EFAULT */
        SCP(sched_getaffinity, -1, 0, 0, 0), /* bad pid: ESRCH */
        SCP(prlimit64, -1, -1, 0, 0),        /* bad resource: EINVAL */
        SCP(umask, 0, 0, 0, 0),              /* query only */
        SCP(uname, 0, 0, 0, 0),              /* NULL: EFAULT */
        SCP(clock_gettime, -1, 0, 0, 0),     /* bad clock: EINVAL */
        SCP(ioctl, -1, 0, 0, 0),             /* bad fd: EBADF */
        SCP(sched_yield, 0, 0, 0, 0),        /* harmless */
        SCP(eventfd2, -1, 0, 0, 0),          /* bad flags: EINVAL */
        SCP(setns, -1, 0, 0, 0),             /* bad fd: EBADF */
        SCP(sched_setattr, -1, 0, 0, 0),     /* bad pid: EINVAL */
        SCP(kcmp, -1, -1, -1, 0),            /* bad pids: ESRCH */
        SCP(process_vm_readv, -1, 0, 0, 0),  /* bad pid: ESRCH */
        SCP(listen, -1, 0, 0, 0),            /* bad fd: EBADF (faked serves on a TCP socket) */
        SCP(accept4, -1, 0, 0, 0),           /* bad fd: EBADF */
        SCP(getsockname, -1, 0, 0, 0),       /* bad fd: EBADF */
        SCP(setsockopt, -1, 0, 0, 0),        /* bad fd: EBADF */
        SCP(setpgid, -1, -1, 0, 0),          /* bad pid: ESRCH/EINVAL */
        SCP(setsid, 0, 0, 0, 0),             /* child session: harmless */
    };
    int n = (int)(sizeof list / sizeof list[0]);
    int blocked = 0;
    fprintf(stderr, "archbox debug [syscall probe] testing %d calls\n", n);
    for (int i = 0; i < n; i++) {
        pid_t p = fork();
        if (p == 0) {
            syscall(list[i].nr, list[i].a, list[i].b, list[i].c, list[i].d);
            _exit(0);
        }
        int st = 0;
        while (waitpid(p, &st, 0) < 0 && errno == EINTR) {}
        if (WIFSIGNALED(st) && WTERMSIG(st) == SIGSYS) {
            fprintf(stderr, "archbox debug [syscall probe] BLOCKED %s (%ld)\n", list[i].name, list[i].nr);
            blocked++;
        } else if (WIFSIGNALED(st)) {
            fprintf(stderr, "archbox debug [syscall probe] %s (%ld) killed by signal %d\n",
                    list[i].name, list[i].nr, WTERMSIG(st));
        }
    }
    fprintf(stderr, "archbox debug [syscall probe] %d of %d blocked\n", blocked, n);
}

/* Debug: which seccomp mode this process runs under (0 none, 1 strict, 2 filter). */
static void dump_seccomp(const char *who) {
    FILE *f = fopen("/proc/self/status", "r");
    if (!f) return;
    char line[256];
    while (fgets(line, sizeof line, f)) {
        if (!strncmp(line, "Seccomp:", 8) || !strncmp(line, "NoNewPrivs:", 11))
            fprintf(stderr, "archbox debug [%s] %s", who, line);
    }
    fclose(f);
}

static int run_fakechroot(const char *root, char **cmd, const char *tty) {
    (void)tty;                                            /* stdin/out/err already are the pty */
    char dir[512], fc[640], fr[640], ld[640], rootlib[640], libpath[1300], preload[1300], excl[1400];
    exe_dir(dir, sizeof dir);
    snprintf(fc, sizeof fc, "%s/libfakechroot.so", dir);
    snprintf(fr, sizeof fr, "%s/libfakeroot.so", dir);
    snprintf(ld, sizeof ld, "%s" LD_SO, root);
    snprintf(rootlib, sizeof rootlib, "%s/usr/lib", root);
    if (access(fc, R_OK) < 0 || access(fr, R_OK) < 0) {
        fprintf(stderr, "archbox: fakechroot tier unavailable: libfakechroot.so/libfakeroot.so not bundled in %s\n", dir);
        return 126;
    }
    if (access(ld, X_OK) < 0) die(ld);
    /* The loader opens libraries with raw syscalls, which fakechroot cannot translate, so
       every library path must be a real host path. */
    snprintf(libpath, sizeof libpath, "%s:%s", dir, rootlib);
    snprintf(preload, sizeof preload, "%s:%s", fc, fr);
    char tmp[640];
    const char *t = getenv("ARCHBOX_TMP");
    snprintf(tmp, sizeof tmp, "%s", (t && *t) ? t : "/tmp");
    snprintf(excl, sizeof excl, "/dev:/proc:/sys:%s:%s", dir, tmp);

    dump_seccomp("before fakechroot");
    scan_syscalls();
    char key[32] = "";
    int have_faked = start_faked(dir, root, ld, libpath, key, sizeof key) == 0;
    if (!have_faked)
        fprintf(stderr, "archbox: warning: faked daemon not running: chown/uid emulation off (no libfakeroot)\n");

    const char *term = getenv("TERM");
    char termbuf[64];
    snprintf(termbuf, sizeof termbuf, "%s", term ? term : "xterm-256color");
    clearenv();
    setenv("TERM", termbuf, 1);
    setenv("HOME", "/root", 1);
    setenv("USER", "root", 1);
    setenv("LOGNAME", "root", 1);
    setenv("SHELL", "/bin/bash", 1);
    setenv("LANG", "C.UTF-8", 1);
    setenv("PATH", "/usr/local/sbin:/usr/local/bin:/usr/bin", 1);
    setenv("GLIBC_TUNABLES", "glibc.pthread.rseq=0", 1);
    /* what `fakechroot` + `chroot <root>` would export */
    setenv("FAKECHROOT", "true", 1);
    setenv("FAKECHROOT_BASE", root, 1);
    setenv("FAKECHROOT_ELFLOADER", ld, 1);                /* execve() of guest ELFs goes via this loader */
    setenv("FAKECHROOT_EXCLUDE_PATH", excl, 1);
    setenv("LD_LIBRARY_PATH", libpath, 1);
    /* what `fakeroot` would export */
    if (have_faked) {
        setenv("FAKEROOTKEY", key, 1);
        setenv("FAKED_MODE", "unknown-is-real", 1);
        setenv("LD_PRELOAD", preload, 1);
    } else {
        setenv("LD_PRELOAD", fc, 1);
    }

    /* argv[0]: absolute guest paths must be turned into host paths for this first exec */
    char prog[700], *argv[64];
    int a = 0;
    argv[a++] = "ld-linux-aarch64.so.1";
    argv[a++] = "--library-path"; argv[a++] = libpath;
    if (cmd[0]) {
        snprintf(prog, sizeof prog, "%s%s", cmd[0][0] == '/' ? root : "", cmd[0]);
        argv[a++] = prog;
        for (int i = 1; cmd[i] && a < 62; i++) argv[a++] = cmd[i];
    } else {
        snprintf(prog, sizeof prog, "%s/usr/bin/bash", root);
        argv[a++] = prog; argv[a++] = "--login"; argv[a++] = "-i";
    }
    argv[a] = NULL;

    if (chdir(root) < 0) die("chdir rootfs");
    child_pid = fork();
    if (child_pid < 0) die("fork");
    if (child_pid == 0) {
        char cwd[700]; snprintf(cwd, sizeof cwd, "%s/root", root);
        if (chdir(cwd) < 0) chdir(root);
        execv(ld, argv);
        fprintf(stderr, "archbox: exec %s: %s\n", ld, strerror(errno));
        _exit(127);
    }
    signal(SIGTERM, forward_fc); signal(SIGHUP, forward_fc); signal(SIGINT, forward_fc);
    int status = 0;
    while (waitpid(child_pid, &status, 0) < 0 && errno == EINTR) {}
    kill_faked();
    if (WIFSIGNALED(status)) {
        int sig = WTERMSIG(status);
        fprintf(stderr, "archbox: fakechroot guest killed by signal %d (%s)%s\n", sig,
                sig == SIGSYS ? "SIGSYS: blocked syscall" : sig == SIGSEGV ? "SIGSEGV" : "other",
                have_faked ? "" : " [faked was not running]");
        dump_seccomp("fakechroot parent");
    }
    return WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
}

/* ---- tier 4 (shim, pinned only): glibc loader + the archbox-rootenv credential shim -----------
   No namespaces, no ptrace, no faked daemon. The loader runs the rootfs binaries with only
   libfakeroot-credentials.so preloaded, so uid/gid/group calls are answered in-process. Crude
   test backend: selected with ARCHBOX_TIER=shim, never by the automatic fallback. */
static int run_shim(const char *root, char **cmd, const char *tty) {
    (void)tty;
    char dir[512], cred[640], ld[640], rootlib[640], libpath[1300];
    exe_dir(dir, sizeof dir);
    snprintf(cred, sizeof cred, "%s/libfakeroot-credentials.so", dir);
    snprintf(ld, sizeof ld, "%s" LD_SO, root);
    snprintf(rootlib, sizeof rootlib, "%s/usr/lib", root);
    if (access(cred, R_OK) < 0) {
        fprintf(stderr, "archbox: shim tier unavailable: %s not bundled\n", cred);
        return 126;
    }
    if (access(ld, X_OK) < 0) die(ld);
    snprintf(libpath, sizeof libpath, "%s:%s", dir, rootlib);
    fprintf(stderr, "archbox debug [shim] preloading %s\n", cred);
    scan_syscalls();   /* same blocked-call table as the fakechroot tier, to compare */
    dbg_file("shim", ld);
    dbg_file("shim", cred);
    dbg_file("shim", rootlib);

    const char *term = getenv("TERM");
    char termbuf[64];
    snprintf(termbuf, sizeof termbuf, "%s", term ? term : "xterm-256color");
    clearenv();
    setenv("TERM", termbuf, 1);
    setenv("HOME", "/root", 1);
    setenv("USER", "root", 1);
    setenv("LOGNAME", "root", 1);
    setenv("SHELL", "/bin/bash", 1);
    setenv("LANG", "C.UTF-8", 1);
    setenv("PATH", "/usr/local/sbin:/usr/local/bin:/usr/bin", 1);
    setenv("GLIBC_TUNABLES", "glibc.pthread.rseq=0", 1);
    setenv("LD_LIBRARY_PATH", libpath, 1);
    setenv("LD_PRELOAD", cred, 1);
    setenv("LD_DEBUG", "libs,files,bindings,statistics", 1);   /* loader trace for the shim guest */

    char prog[700], *argv[64];
    int a = 0;
    argv[a++] = "ld-linux-aarch64.so.1";
    argv[a++] = "--library-path"; argv[a++] = libpath;
    if (cmd[0]) {
        snprintf(prog, sizeof prog, "%s%s", cmd[0][0] == '/' ? root : "", cmd[0]);
        argv[a++] = prog;
        for (int i = 1; cmd[i] && a < 62; i++) argv[a++] = cmd[i];
    } else {
        snprintf(prog, sizeof prog, "%s/usr/bin/bash", root);
        argv[a++] = prog; argv[a++] = "--login"; argv[a++] = "-i";
    }
    argv[a] = NULL;

    if (chdir(root) < 0) die("chdir rootfs");
    child_pid = fork();
    if (child_pid < 0) die("fork");
    if (child_pid == 0) {
        char cwd[700]; snprintf(cwd, sizeof cwd, "%s/root", root);
        if (chdir(cwd) < 0) chdir(root);
        execv(ld, argv);
        fprintf(stderr, "archbox: exec %s: %s\n", ld, strerror(errno));
        _exit(127);
    }
    signal(SIGTERM, forward_fc); signal(SIGHUP, forward_fc); signal(SIGINT, forward_fc);
    mon_pid = child_pid; mon_run = 1;
    pthread_t mt; int have_mon = pthread_create(&mt, NULL, mon_thread, NULL) == 0;
    int status = 0;
    while (waitpid(child_pid, &status, 0) < 0 && errno == EINTR) {}
    if (have_mon) { mon_run = 0; pthread_join(mt, NULL); }
    mon_report();
    dbg_proc("shim parent", "/proc/self/status");
    if (WIFSIGNALED(status))
        fprintf(stderr, "archbox: shim guest killed by signal %d\n", WTERMSIG(status));
    return WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
}

int main(int argc, char **argv) {
    dbg_startup("launcher");
    dbg_env("launcher");
    if (argc < 2) { fprintf(stderr, "usage: %s <rootfs> [cmd...]\n", argv[0]); return 2; }
    const char *root = argv[1];
    uid_t uid = getuid(); gid_t gid = getgid();
    const char *tty = ttyname(0);
    char ttybuf[128] = "";
    if (tty) { snprintf(ttybuf, sizeof ttybuf, "%s", tty); tty = ttybuf; }

    /* One namespace at a time so a failure names the culprit (EINVAL on a combined call only
       says "some flag is unsupported"). The user namespace goes first and is mapped before the
       others, which then run with full caps inside it. */
    /* Tier selection: unshare -> proot -> fakechroot.
       ARCHBOX_TIER=unshare|proot|fakechroot pins one tier (ARCHBOX_FORCE_PROOT=1 is kept as
       an alias for proot). Otherwise each tier must prove itself before the next is tried. */
    const char *ver = getenv("ARCHBOX_VERSION");
    const char *pin = getenv("ARCHBOX_TIER");
    if (!pin && getenv("ARCHBOX_FORCE_PROOT")) pin = "proot";
    if (pin && !strcmp(pin, "proot")) {
        fprintf(stderr, "archbox %s: tier pinned to proot\n", ver ? ver : "?");
        return run_proot(root, argv + 2, tty, 0);
    }
    if (pin && !strcmp(pin, "shim")) {
        fprintf(stderr, "archbox %s: tier pinned to shim\n", ver ? ver : "?");
        return run_shim(root, argv + 2, tty);
    }
    if (pin && !strcmp(pin, "fakechroot")) {
        fprintf(stderr, "archbox %s: tier pinned to fakechroot\n", ver ? ver : "?");
        return run_fakechroot(root, argv + 2, tty);
    }
    int ns = (pin && !strcmp(pin, "unshare")) ? 0 : probe_namespaces();
    if (ns != 0) {
        fprintf(stderr, "archbox %s: no namespaces (%s)\n", ver ? ver : "?", ns > 0 ? strerror(ns) : "blocked");
        diagnose();
        fprintf(stderr, "archbox %s: testing proot...\n", ver ? ver : "?");
        if (proot_works(root, tty)) {
            fprintf(stderr, "archbox %s: proot ok, using proot\n", ver ? ver : "?");
            return run_proot(root, argv + 2, tty, 0);
        }
        fprintf(stderr, "archbox %s: proot failed its filesystem self-test, falling back to fakechroot\n", ver ? ver : "?");
        return run_fakechroot(root, argv + 2, tty);
    }
    fprintf(stderr, "archbox %s: using namespaces\n", ver ? ver : "?");

    if (unshare(CLONE_NEWUSER) < 0) {
        int e = errno;
        fprintf(stderr, "archbox: unshare(user): %s\n", strerror(e));
        diagnose();
        return 126;
    }
    char map[64];
    write_file("/proc/self/setgroups", "deny");
    snprintf(map, sizeof map, "0 %u 1\n", uid);  write_file("/proc/self/uid_map", map);
    snprintf(map, sizeof map, "0 %u 1\n", gid);  write_file("/proc/self/gid_map", map);

    if (unshare(CLONE_NEWNS) < 0)  { fprintf(stderr, "archbox: unshare(mnt): %s\n", strerror(errno));  diagnose(); return 126; }
    if (unshare(CLONE_NEWPID) < 0) { fprintf(stderr, "archbox: unshare(pid): %s\n", strerror(errno));  diagnose(); return 126; }
    if (unshare(CLONE_NEWUTS) < 0) warn("uts namespace skipped");
    if (unshare(CLONE_NEWIPC) < 0) warn("ipc namespace skipped");

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
