# Archbox

Android app (arm64) that is a terminal and nothing else. On first launch it downloads
Arch Linux ARM, unpacks it, then boots it in user/mount/pid/uts/ipc/cgroup namespaces.
The app's pty is exposed inside as `/dev/console` and `/dev/ttyS0`. The container only
runs glibc (it execs `ld-linux-aarch64.so.1` directly after `pivot_root`; Android's bionic
is not reachable from inside).

Built by GitHub Actions: grab the APK from the workflow run's artifacts.

Limits: needs a ROM/kernel that lets apps `unshare(CLONE_NEWUSER)`. Only one uid/gid is
mapped, so packages that `chown` to other users can fail. No network namespace (no
CAP_NET_ADMIN on the host to wire one up).
