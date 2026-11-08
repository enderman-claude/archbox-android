#!/usr/bin/env bash
# Produces libfakechroot.so, libfakeroot.so and libfaked.so (glibc, aarch64) in $1 (default:
# app/src/main/jniLibs/arm64-v8a). They run under the Arch rootfs's glibc, so they must be glibc
# objects (NOT built with the NDK). Each component is tried with several methods in order and the
# first one that yields a valid aarch64 ELF wins; a failing method never aborts the script.
#
#   libfakechroot.so : 1. Debian-patched source (builds on glibc >= 2.33; vanilla 2.20.1 does not)
#                      2. prebuilt arm64 .deb from the Ubuntu ports pool
#                      3. prebuilt package from the Arch Linux ARM repos
#   libfakeroot.so + libfaked.so (TCP flavour, SysV IPC is blocked on Android):
#                      1. upstream source tarball (sha256 pinned)
#                      2. prebuilt arm64 .deb(s) from the Ubuntu ports pool
#                      3. prebuilt package from the Arch Linux ARM repos
#
# Always exits 0 (the tier is optional); it prints ::notice::/::warning:: annotations saying which
# method produced what, or what is missing.
set -u
J=$(cd "${1:-app/src/main/jniLibs/arm64-v8a}" 2>/dev/null && pwd || { mkdir -p "${1:-app/src/main/jniLibs/arm64-v8a}" && cd "${1:-app/src/main/jniLibs/arm64-v8a}" && pwd; })
W=$(mktemp -d)
trap 'rm -rf "$W"' EXIT
export CC=${CC:-aarch64-linux-gnu-gcc}
UBUNTU=https://archive.ubuntu.com/ubuntu/pool
PORTS=${PORTS:-http://ports.ubuntu.com/ubuntu-ports/pool}
ARCH_MIRRORS=${ARCH_MIRRORS:-"https://mirrors.dotsrc.org/archlinuxarm https://de3.mirror.archlinuxarm.org https://ca.us.mirror.archlinuxarm.org https://fl.us.mirror.archlinuxarm.org https://tw.mirror.archlinuxarm.org"}
ARCH_REPOS="core extra alarm"

FC_SRC=missing; FR_SRC=missing

log()  { echo "[fakechroot] $*" >&2; }
fetch() { curl -fsSL --retry 3 --connect-timeout 20 -o "$2" "$1"; }
sum()  { echo "$2  $1" | sha256sum -c - >/dev/null; }
is_aarch64_elf() { [ -s "$1" ] && file -L "$1" | grep -q 'ELF 64-bit.*aarch64'; }
# install $1 as $J/$2 if it is a valid aarch64 ELF
put() {
    is_aarch64_elf "$1" && cp -L "$1" "$J/$2" && chmod 755 "$J/$2" || return 1
    aarch64-linux-gnu-strip --strip-unneeded "$J/$2" 2>/dev/null   # best effort: smaller APK
    return 0
}
have_fc() { is_aarch64_elf "$J/libfakechroot.so"; }
have_fr() { is_aarch64_elf "$J/libfakeroot.so" && is_aarch64_elf "$J/libfaked.so"; }
clean_fc() { rm -f "$J/libfakechroot.so"; }
clean_fr() { rm -f "$J/libfakeroot.so" "$J/libfaked.so"; }
newest() { # $1 = directory index URL, $2 = ERE of file names -> newest match
    curl -fsSL --retry 2 --connect-timeout 20 "$1" 2>/dev/null | grep -oE "$2" | sort -uV | tail -1
}
first_find() { # $1 = dir, rest = names in order of preference
    local d=$1 n f; shift
    for n in "$@"; do f=$(find "$d" -name "$n" \( -type f -o -type l \) 2>/dev/null | head -1); [ -n "$f" ] && { echo "$f"; return 0; }; done
    return 1
}

# ---------------------------------------------------------------- fakechroot
fc_source() {
    local d=$W/fc B=$UBUNTU/universe/f/fakechroot
    mkdir -p "$d" && cd "$d" || return 1
    fetch "$B/fakechroot_2.20.1+ds.orig.tar.xz" orig.tar.xz && sum orig.tar.xz 138a31f79cfa3bd21718e424e0a7f640916a0586593934a854833cd6436409e4 || return 1
    fetch "$B/fakechroot_2.20.1+ds-19.debian.tar.xz" deb.tar.xz && sum deb.tar.xz 90b1adc171ef03fbae634c3949ee66832ff26d95b46b6e81d646e4a6609803d5 || return 1
    mkdir src && tar xJf orig.tar.xz -C src --strip-components=1 && tar xJf deb.tar.xz -C src && cd src || return 1
    local p
    for p in $(grep -v '^#' debian/patches/series); do patch -p1 -s < "debian/patches/$p" || return 1; done
    autoreconf -fi >/dev/null 2>&1 || return 1
    ./configure --host=aarch64-linux-gnu >/dev/null 2>&1 || return 1
    make -j"$(nproc)" >/dev/null 2>&1 || return 1
    put src/.libs/libfakechroot.so libfakechroot.so
}
fc_deb() {
    local d=$W/fcdeb f; mkdir -p "$d" && cd "$d" || return 1
    f=$(newest "$PORTS/universe/f/fakechroot/" 'libfakechroot_[^"<> ]*_arm64\.deb') && [ -n "$f" ] || return 1
    fetch "$PORTS/universe/f/fakechroot/$f" x.deb && dpkg-deb -x x.deb root || return 1
    put "$(first_find root libfakechroot.so)" libfakechroot.so
}
arch_pkg() { # $1 = package name, $2 = dest dir -> extracts newest aarch64 package from any mirror
    local pkg=$1 dest=$2 m r f
    mkdir -p "$dest"
    for m in $ARCH_MIRRORS; do
        for r in $ARCH_REPOS; do
            f=$(newest "$m/aarch64/$r/" "$pkg-[0-9][^\"<> /]*-aarch64\\.pkg\\.tar\\.(zst|xz)") || true
            [ -n "$f" ] || continue
            fetch "$m/aarch64/$r/$f" "$dest/pkg.tar" && tar -xf "$dest/pkg.tar" -C "$dest" && return 0
        done
    done
    return 1
}
fc_arch() {
    local d=$W/fcarch; arch_pkg fakechroot "$d" || return 1
    put "$(first_find "$d" libfakechroot.so)" libfakechroot.so
}

# ---------------------------------------------------------------- fakeroot
fr_source() {
    local d=$W/fr; mkdir -p "$d" && cd "$d" || return 1
    fetch "$UBUNTU/main/f/fakeroot/fakeroot_1.37.1.2.orig.tar.gz" fr.tgz && sum fr.tgz 959496928c8a676ec8377f665ff6a19a707bfad693325f9cc4a4126642f53224 || return 1
    mkdir src && tar xzf fr.tgz -C src --strip-components=1 && cd src || return 1
    # capset/capget: no libcap for the cross target, and nothing here needs them
    ./configure --host=aarch64-linux-gnu --with-ipc=tcp ac_cv_func_capset=no ac_cv_func_capget=no >/dev/null 2>&1 || return 1
    make -j"$(nproc)" >/dev/null 2>&1 || return 1
    put .libs/libfakeroot.so libfakeroot.so && put faked libfaked.so
}
fr_deb() {
    local d=$W/frdeb f1 f2; mkdir -p "$d" && cd "$d" || return 1
    f1=$(newest "$PORTS/main/f/fakeroot/" 'libfakeroot_[^"<> ]*_arm64\.deb') && [ -n "$f1" ] || return 1
    f2=$(newest "$PORTS/main/f/fakeroot/" 'fakeroot_[^"<> ]*_arm64\.deb') && [ -n "$f2" ] || return 1
    fetch "$PORTS/main/f/fakeroot/$f1" a.deb && dpkg-deb -x a.deb root && fetch "$PORTS/main/f/fakeroot/$f2" b.deb && dpkg-deb -x b.deb root || return 1
    put "$(first_find root libfakeroot-tcp.so)" libfakeroot.so && put "$(first_find root faked-tcp)" libfaked.so
}
fr_arch() {
    local d=$W/frarch; arch_pkg fakeroot "$d" || return 1
    put "$(first_find "$d" libfakeroot-tcp.so libfakeroot.so libfakeroot-0.so)" libfakeroot.so && put "$(first_find "$d" faked-tcp faked)" libfaked.so
}

# ---------------------------------------------------------------- drive
skipped() { case " ${FC_SKIP:-} " in *" $1 "*) return 0;; esac; return 1; }   # FC_SKIP="fc_source fr_deb": test hook
for m in fc_source fc_deb fc_arch; do
    have_fc && break
    skipped "$m" && { log "$m skipped (FC_SKIP)"; continue; }
    log "libfakechroot: trying $m"
    ( "$m" ) >/dev/null 2>&1 || true
    if have_fc; then FC_SRC=$m; else clean_fc; log "libfakechroot: $m failed"; fi
done
for m in fr_source fr_deb fr_arch; do
    have_fr && break
    skipped "$m" && { log "$m skipped (FC_SKIP)"; continue; }
    log "libfakeroot/faked: trying $m"
    ( "$m" ) >/dev/null 2>&1 || true
    if have_fr; then FR_SRC=$m; else clean_fr; log "libfakeroot/faked: $m failed"; fi
done

file "$J"/libfakechroot.so "$J"/libfakeroot.so "$J"/libfaked.so 2>&1 | sed 's/^/[fakechroot] /' >&2
if have_fc && have_fr; then
    echo "::notice title=fakechroot-tier::complete: libfakechroot via $FC_SRC, libfakeroot/faked via $FR_SRC"
elif have_fc; then
    echo "::warning title=fakechroot-tier::libfakechroot via $FC_SRC, but libfakeroot/faked MISSING (uid/chown emulation will be off)"
else
    echo "::warning title=fakechroot-tier::libfakechroot MISSING after all methods (source, ubuntu-ports deb, archlinuxarm): the fakechroot tier will report unavailable"
fi
exit 0
