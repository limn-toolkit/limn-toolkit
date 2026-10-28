#!/bin/bash
# build.sh: the demo as one native executable, from the metadata agent-run.sh gathered plus the
# resources it cannot see (metadata/<platform>/resources/), into build/native-image/.
#
#   GRAALVM_HOME, DEMO_JAR, PLATFORM  as in agent-run.sh
#   JOBS                              cores native-image may use (default 4)
#
# Extra arguments go to native-image. -H:+IncludeAllLocales is on because an image otherwise
# carries only the build machine's locale data, and the Showcase then shows its own translations
# with dates and money formatted for the wrong country.
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
meta="$here/metadata/${PLATFORM:-$default}"
out="$here/../../build/native-image"
mkdir -p "$out"
exec "$GRAALVM_HOME/bin/native-image" -J-Xmx6g --parallelism="${JOBS:-4}" \
  --enable-native-access=ALL-UNNAMED -H:+IncludeAllLocales \
  -H:ConfigurationFileDirectories="$meta,$meta/resources" \
  "$@" -jar "$DEMO_JAR" -o "$out/limn-demo"
