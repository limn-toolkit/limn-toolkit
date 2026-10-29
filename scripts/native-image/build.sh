#!/bin/bash
# build.sh: the demo as one native executable, into build/native-image/. What the toolkit needs
# in an image (its resources, its reflection and JNI, and the native libraries of this platform)
# comes in its own jars; metadata/ lists only the demo's own files.
#
#   GRAALVM_HOME  a GraalVM for JDK 25
#   DEMO_JAR      limn-demo-all.jar from ./gradlew :limn-demo:fatJar
#   JOBS          cores native-image may use (default 4)
#   XMX           the builder's heap (default 6g; 3g fits a 4 GB machine with swap)
#
# Extra arguments go to native-image. -H:+IncludeAllLocales is on because an image otherwise
# carries only the build machine's locale data, and the Showcase then shows its own translations
# with dates and money formatted for the wrong country.
set -u
here=$(cd "$(dirname "$0")" && pwd)
: "${GRAALVM_HOME:?set GRAALVM_HOME to a GraalVM for JDK 25}"
: "${DEMO_JAR:?set DEMO_JAR to limn-demo-all.jar}"
out="$here/../../build/native-image"
mkdir -p "$out"
exec "$GRAALVM_HOME/bin/native-image" -J-Xmx"${XMX:-6g}" --parallelism="${JOBS:-4}" \
  -H:+IncludeAllLocales \
  -H:ConfigurationFileDirectories="$here/metadata" \
  "$@" -jar "$DEMO_JAR" -o "$out/limn-demo"
