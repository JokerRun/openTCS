#!/bin/sh
#
# Start the openTCS kernel.
#

# Set base directory names.
export OPENTCS_BASE=.
export OPENTCS_HOME=.
export OPENTCS_CONFIGDIR="${OPENTCS_HOME}/config"
export OPENTCS_LIBDIR="${OPENTCS_BASE}/lib"

# Set the class path
export OPENTCS_CP="${OPENTCS_LIBDIR}/*"
export OPENTCS_CP="${OPENTCS_CP}:${OPENTCS_LIBDIR}/openTCS-extensions/*"

if [ -n "${OPENTCS_JAVAVM}" ]; then
    export JAVA="${OPENTCS_JAVAVM}"
else
    # XXX Be a bit more clever to find out the name of the JVM runtime.
    export JAVA="java"
fi

# Start kernel
${JAVA} -enableassertions \
    -Dopentcs.base="/Users/rico/AnypointStudio/workspace/Git/Laurus/openTCS/openTCS-4.16.1-src/openTCS-Kernel/build/install/openTCS-Kernel" \
    -Dopentcs.home="/Users/rico/AnypointStudio/workspace/Git/Laurus/openTCS/openTCS-4.16.1-src/openTCS-Kernel/build/install/openTCS-Kernel" \
    -Djava.util.logging.config.file=src/dist/config/logging.config \
    -Djava.security.policy=file:src/dist/config/java.policy \
    -XX:-OmitStackTraceInFastThrow \
    -classpath "${OPENTCS_CP}" \
    org.opentcs.kernel.RunKernel
