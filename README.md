# Archbox

Android app (arm64) that is a terminal and nothing else. On first launch it downloads
Arch Linux ARM, unpacks it, then boots it in user/mount/pid/uts/ipc/cgroup namespaces.
The app's pty is exposed inside as `/dev/console` and `/dev/ttyS0`. The container only
runs glibc (it execs `ld-linux-aarch64.so.1` directly after `pivot_root`; Android's bionic
is not reachable from inside).

First launch shows an onboarding screen: download Arch Linux ARM (resumable), reuse the cached
download, or pick a `.tar.gz` / `.tar.xz` / `.tar` from storage. The install runs in a foreground
service, so minimising the app does not interrupt it. The downloaded tarball is kept for reinstalls
and can also be saved outside the app (survives uninstalling it).

Backends, tried in order: user namespaces, then PRoot (only if it passes a filesystem self-test),
then fakechroot+fakeroot. Pin one from the terminal's menu (the three dots on the key bar).

Built by GitHub Actions: grab the APK from the workflow run's artifacts.

Limits: needs a ROM/kernel that lets apps `unshare(CLONE_NEWUSER)`. Only one uid/gid is
mapped, so packages that `chown` to other users can fail. No network namespace (no
CAP_NET_ADMIN on the host to wire one up).
