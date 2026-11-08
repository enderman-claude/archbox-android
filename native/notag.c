/*
 * libnotag.so: linked into the PRoot binaries (DT_NEEDED, added with patchelf in CI).
 *
 * A program started by an app (unlike the app process itself) gets Android's default heap
 * pointer tagging (TBI): malloc() returns addresses like 0xb4000071..., and ptrace(PEEKDATA)
 * refuses tagged addresses with EIO. PRoot reads the tracee's execve path and arguments from
 * memory it malloc'ed before fork(), so every translated syscall failed (and Android's seccomp
 * filter turned the voided syscall into ENOSYS). Turning tagging off before proot's main()
 * runs makes all of its later allocations plain pointers.
 */
#include <malloc.h>

#ifndef M_BIONIC_SET_HEAP_TAGGING_LEVEL
#define M_BIONIC_SET_HEAP_TAGGING_LEVEL (-204)
#endif
#ifndef M_HEAP_TAGGING_LEVEL_NONE
#define M_HEAP_TAGGING_LEVEL_NONE 0
#endif

__attribute__((constructor(101))) static void archbox_notag(void) {
    mallopt(M_BIONIC_SET_HEAP_TAGGING_LEVEL, M_HEAP_TAGGING_LEVEL_NONE);
}
