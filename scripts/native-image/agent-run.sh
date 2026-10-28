#!/bin/bash
# agent-run.sh <scene>...: runs each demo scene once on GraalVM's JVM under the tracing agent and
# merges what it reads reflectively, as a resource or through JNI into metadata/<platform>/.
#
# Run it on the machine whose image you want, inside a desktop session (on macOS over ssh:
# sudo launchctl asuser 501 sudo -u <user> scripts/native-image/agent-run.sh app table ...).
# A scene is taken in screenshot mode, so no window shows; --exit-after ends the few scenes that
# never capture (menubar, split-states), which otherwise wait for events forever.
#
#   GRAALVM_HOME  GraalVM for JDK 25 (its bin/java and the agent)
#   DEMO_JAR      limn-demo-all.jar from ./gradlew :limn-demo:fatJar
#   PLATFORM      metadata directory name, e.g. macos-aarch64 (default: from uname)
set -u
here=$(cd "$(dirname "$0")" && pwd)
: "${GRAALVM_HOME:?set GRAALVM_HOME to a GraalVM for JDK 25}"
: "${DEMO_JAR:?set DEMO_JAR to limn-demo-all.jar}"
case "$(uname -s)-$(uname -m)" in
  Darwin-arm64) default=macos-aarch64 ;;
  Darwin-x86_64) default=macos-x86_64 ;;
  Linux-aarch64) default=linux-aarch64 ;;
  *) default=linux-x86_64 ;;
esac
out="$here/metadata/${PLATFORM:-$default}"
mkdir -p "$out"
first=()
[ "$(uname -s)" = Darwin ] && first=(-XstartOnFirstThread)
for scene in "$@"; do
  "$GRAALVM_HOME/bin/java" "${first[@]}" --enable-native-access=ALL-UNNAMED \
    --sun-misc-unsafe-memory-access=allow \
    -agentlib:native-image-agent=config-merge-dir="$out" \
    -jar "$DEMO_JAR" --scene "$scene" --screenshot "/tmp/limn-agent-$scene.png" \
    --exit-after 15000 > "/tmp/limn-agent-$scene.log" 2>&1
  echo "$scene exit $?"
done
