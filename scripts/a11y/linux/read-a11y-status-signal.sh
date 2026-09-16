#!/usr/bin/env bash
# Read whether the desktop's accessibility switch announces its own changes.
#
# Run this ON THE MACHINE, inside the user's graphical session environment (at least
# DBUS_SESSION_BUS_ADDRESS must name the session bus). The Linux bridge gates itself on
# org.a11y.Status.IsEnabled, which at-spi-bus-launcher owns on the session bus, and watches that
# flag through org.freedesktop.DBus.Properties.PropertiesChanged rather than reading it once. This
# script is the reading that watch rests on: which process owns org.a11y.Bus, which bus daemon
# routes for it, what the Status interface says about its properties, and -- with --flip -- the
# exact signal the launcher sends when IsEnabled changes, seen twice:
#
#   1. through a subscriber that asks the bus with AddMatch on sender='org.a11y.Bus' (gdbus monitor),
#      which is the routing a bridge relies on: a match on the WELL-KNOWN name, resolved by the bus;
#   2. through dbus-monitor, which prints the sender's unique name, the path, the interface, the
#      member and the body's signature.
#
#     read-a11y-status-signal.sh [--flip]
#
# Without --flip it only reads. With --flip it sets IsEnabled to the opposite of what it read,
# waits, sets it back, and then restores the GSettings key org.gnome.desktop.interface
# toolkit-accessibility to exactly what it was (explicitly set, or reset to its default), because
# the launcher writes that key when IsEnabled is set over D-Bus. It never touches
# ScreenReaderEnabled, which on some desktops starts a screen reader.
#
# Needs busctl (systemd), gdbus (GLib), dbus-monitor (dbus tools); gsettings/dconf when present.
set -uo pipefail

flip=0
if [ "${1:-}" = "--flip" ]; then
    flip=1
elif [ $# -gt 0 ]; then
    sed -n '2,26p' "$0" >&2
    exit 2
fi

if [ -z "${DBUS_SESSION_BUS_ADDRESS:-}" ]; then
    echo "DBUS_SESSION_BUS_ADDRESS is not set: run inside the graphical session's environment" >&2
    exit 1
fi

say() { printf '%s\n' "$*"; }
run() { say "\$ $*"; "$@" 2>&1; say "(exit $?)"; }

get_flag() {
    busctl --user --auto-start=no get-property org.a11y.Bus /org/a11y/bus org.a11y.Status "$1" 2>&1
}

say "==== machine"
run date -u '+%Y-%m-%dT%H:%M:%SZ'
run uname -srm
if [ -r /etc/os-release ]; then
    say "\$ grep PRETTY_NAME /etc/os-release"; grep PRETTY_NAME /etc/os-release
fi
launcher=$(command -v at-spi-bus-launcher || echo /usr/libexec/at-spi-bus-launcher)
if command -v rpm >/dev/null 2>&1; then
    run rpm -qf "$launcher"
    run rpm -q dbus-broker dbus-daemon glib2
elif command -v dpkg >/dev/null 2>&1; then
    run dpkg -S "$launcher"
    say "\$ dpkg -l at-spi2-core dbus-daemon dbus-broker libglib2.0-bin | grep ^ii"
    dpkg -l at-spi2-core dbus-daemon dbus-broker libglib2.0-bin 2>/dev/null | grep '^ii'
fi
say "\$ sha256sum $launcher"; sha256sum "$launcher" 2>&1

say ""
say "==== who routes and who owns org.a11y.Bus"
say "\$ ps -o pid=,args= -u \"$(id -u)\" | grep -E 'dbus-(broker|daemon)|at-spi'"
ps -o pid=,args= -u "$(id -u)" | grep -E 'dbus-(broker|daemon)|at-spi' | grep -v grep
run busctl --user --auto-start=no status org.a11y.Bus --no-pager
say "\$ busctl --user call org.freedesktop.DBus / org.freedesktop.DBus GetNameOwner s org.a11y.Bus"
busctl --user --auto-start=no call org.freedesktop.DBus /org/freedesktop/DBus \
    org.freedesktop.DBus GetNameOwner s org.a11y.Bus 2>&1

say ""
say "==== org.a11y.Status as introspected"
run busctl --user --auto-start=no introspect org.a11y.Bus /org/a11y/bus org.a11y.Status --no-pager
say "\$ busctl --user introspect org.a11y.Bus /org/a11y/bus --xml-interface | (org.a11y.Status only)"
busctl --user --auto-start=no introspect org.a11y.Bus /org/a11y/bus --xml-interface 2>&1 \
    | sed -n '/org.a11y.Status/,/<\/interface>/p'

say ""
say "==== the flags"
say "IsEnabled: $(get_flag IsEnabled)"
say "ScreenReaderEnabled: $(get_flag ScreenReaderEnabled)"
key_before=""
if command -v dconf >/dev/null 2>&1; then
    key_before=$(dconf read /org/gnome/desktop/interface/toolkit-accessibility 2>/dev/null)
    say "dconf /org/gnome/desktop/interface/toolkit-accessibility: '${key_before}' (empty = not set, default applies)"
fi

if [ $flip -eq 0 ]; then
    say ""
    say "(read only; pass --flip to observe the signal)"
    exit 0
fi

before=$(get_flag IsEnabled)
case "$before" in
    "b true") opposite=false; original=true ;;
    "b false") opposite=true; original=false ;;
    *) say "IsEnabled did not read as a boolean ('$before'); not flipping"; exit 1 ;;
esac

tmp=$(mktemp -d)
gdbus monitor --session --dest org.a11y.Bus --object-path /org/a11y/bus >"$tmp/gdbus.txt" 2>&1 &
gdbus_pid=$!
dbus-monitor --session \
    "type='signal',interface='org.freedesktop.DBus.Properties',member='PropertiesChanged',path='/org/a11y/bus'" \
    >"$tmp/dbus-monitor.txt" 2>&1 &
monitor_pid=$!
sleep 2

say ""
say "==== flip: IsEnabled $original -> $opposite -> $original"
say "\$ busctl --user set-property org.a11y.Bus /org/a11y/bus org.a11y.Status IsEnabled b $opposite"
busctl --user --auto-start=no set-property org.a11y.Bus /org/a11y/bus org.a11y.Status IsEnabled b "$opposite" 2>&1
say "(exit $?) at $(date -u '+%H:%M:%S.%N')"
sleep 2
say "IsEnabled now: $(get_flag IsEnabled)"
say "\$ busctl --user set-property org.a11y.Bus /org/a11y/bus org.a11y.Status IsEnabled b $original"
busctl --user --auto-start=no set-property org.a11y.Bus /org/a11y/bus org.a11y.Status IsEnabled b "$original" 2>&1
say "(exit $?) at $(date -u '+%H:%M:%S.%N')"
sleep 2
say "IsEnabled now: $(get_flag IsEnabled)"
say "\$ busctl --user set-property ... IsEnabled b $original   (the same value again: is a no-change announced?)"
busctl --user --auto-start=no set-property org.a11y.Bus /org/a11y/bus org.a11y.Status IsEnabled b "$original" 2>&1
say "(exit $?) at $(date -u '+%H:%M:%S.%N')"
sleep 2

kill "$gdbus_pid" "$monitor_pid" 2>/dev/null
wait "$gdbus_pid" "$monitor_pid" 2>/dev/null

if command -v dconf >/dev/null 2>&1; then
    key_after=$(dconf read /org/gnome/desktop/interface/toolkit-accessibility 2>/dev/null)
    if [ -z "$key_before" ] && [ -n "$key_after" ]; then
        say "\$ dconf reset /org/gnome/desktop/interface/toolkit-accessibility   (it was not set before)"
        dconf reset /org/gnome/desktop/interface/toolkit-accessibility 2>&1
    fi
    say "dconf toolkit-accessibility after: '$(dconf read /org/gnome/desktop/interface/toolkit-accessibility 2>/dev/null)' (before: '${key_before}')"
fi
say "IsEnabled after: $(get_flag IsEnabled) (before: $before)"

say ""
say "==== gdbus monitor --session --dest org.a11y.Bus --object-path /org/a11y/bus (AddMatch on the well-known name)"
cat "$tmp/gdbus.txt"
say ""
say "==== dbus-monitor, PropertiesChanged on /org/a11y/bus"
cat "$tmp/dbus-monitor.txt"
rm -rf "$tmp"
