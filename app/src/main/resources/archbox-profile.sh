#!/bin/sh
# Sourced by /etc/profile for login shells (written by the Archbox app on every launch).
echo "libc: $(ldd --version 2>&1 | head -1)"

if [ ! -e /var/lib/pacman/.archbox-keyring ]; then
    echo 'First login: initialising the pacman keyring (one time; Ctrl+C skips it)...'
    _kl=/tmp/.archbox-keyring.log
    _kdir=/etc/pacman.d/gnupg

    # Everything pacman-key/gpg leave behind (agents, half-dead children).
    _archbox_reap() {
        timeout 5 gpgconf --homedir "$_kdir" --kill all >/dev/null 2>&1
        for _d in /proc/[0-9]*; do
            read -r _c < "$_d/comm" 2>/dev/null || continue
            case $_c in gpg-agent|dirmngr|keyboxd|scdaemon|gpg|gpgconf|pacman-key) kill -KILL "${_d#/proc/}" 2>/dev/null ;; esac
        done
    }

    # pacman-key runs as a background job that this shell merely watches: if it (or proot under
    # it) wedges after its work is done, the login still continues, and Ctrl+C always works here.
    case $- in *m*) _jm=1 ;; *) _jm= ;; esac
    set +m
    : > "$_kl"
    ( pacman-key --init && pacman-key --populate archlinuxarm ) > "$_kl" 2>&1 &
    _kp=$!
    disown "$_kp" 2>/dev/null          # no "Killed" job notices if we have to stop it
    _int=
    trap '_int=1' INT

    _off=0; _idle=0; _t=0; _why=
    while :; do
        _sz=$(wc -c < "$_kl")
        if [ "$_sz" -gt "$_off" ]; then
            tail -c +$((_off + 1)) "$_kl"
            _off=$_sz; _idle=0
        else
            _idle=$((_idle + 1))
        fi
        if ! kill -0 "$_kp" 2>/dev/null; then _why=exited; break; fi
        if [ -n "$_int" ]; then _why=interrupted; break; fi
        # the last step of --populate printed its trust-db summary and nothing has happened since
        if [ "$_idle" -ge 20 ] && grep -q 'Importing owner trust values' "$_kl" \
           && [ "$(grep -c 'Updating trust database' "$_kl")" -ge 2 ]; then _why=stuck-after-done; break; fi
        # nothing at all for 5 minutes
        if [ "$_idle" -ge 600 ]; then _why=timeout; break; fi
        sleep 0.5
    done
    trap - INT

    case $_why in
        stuck-after-done|timeout|interrupted)
            [ "$_why" = timeout ] && echo "archbox: pacman-key made no progress for 5 minutes; process state:"
            [ "$_why" != interrupted ] && { ps -eo pid,ppid,stat,wchan:18,comm 2>/dev/null | grep -E 'PID|pacman|gpg|dirmngr|keyboxd|proot' | head -20; }
            [ "$_why" = stuck-after-done ] && echo "archbox: pacman-key finished its work but did not exit; stopping it."
            kill -KILL "$_kp" 2>/dev/null
            _archbox_reap
            ;;
    esac
    [ -n "$_jm" ] && set -m

    if grep -q 'Importing owner trust values' "$_kl" 2>/dev/null; then
        touch /var/lib/pacman/.archbox-keyring
    else
        echo "archbox: keyring not initialised ($_why). Run: pacman-key --init && pacman-key --populate archlinuxarm"
    fi
    unset _kl _kdir _kp _off _sz _idle _t _why _int _jm _d _c
    unset -f _archbox_reap
fi
