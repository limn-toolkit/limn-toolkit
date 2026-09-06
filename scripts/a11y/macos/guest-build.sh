#!/bin/zsh
# Runs IN THE GUEST (macOS 26.6 at 192.168.64.2, user activities). Builds the phase 7 probe and its
# clients. The provider is compiled against a fat jar only for LWJGL: the probe reaches ObjC through
# LWJGL's ObjCRuntime and libffi, which is what the bridge will do, and nothing else in the jar is
# used.
set -e
HERE=${1:-/Users/activities/limn-a11y7}
JAVA_HOME=/Users/activities/jdk21/Contents/Home
FATJAR=${FATJAR:-/Users/activities/limn-a11y/limn-demo-all.jar}

rm -rf "$HERE/classes"; mkdir -p "$HERE/classes"
"$JAVA_HOME/bin/javac" --release 17 -Xlint:all -cp "$FATJAR" -d "$HERE/classes" "$HERE/AxProbe.java"
for client in axtree; do
    /usr/bin/swiftc -O -o "$HERE/$client" "$HERE/$client.swift"
done
echo "built: $HERE/classes/AxProbe.class $HERE/axtree"
