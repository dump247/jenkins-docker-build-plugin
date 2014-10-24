#!/bin/bash

set -o errexit # Exit if any command fials
set -o nounset # Don't use unset variables

SLAVE_JAR_PATH="%s"
SLAVE_JAR_URL="%s"

if [ -f "$SLAVE_JAR_PATH" ]; then
    cp -f "$SLAVE_JAR_PATH" /tmp/slave.jar
else
    if [ -n "${SLAVE_JAR_PATH}" ]; then
        echo "Slave Jar not found: ${SLAVE_JAR_PATH}" 1>&2
    fi

    echo "Attempting to download slave jar from master: ${SLAVE_JAR_URL}" 1>&2

    if which curl >/dev/null; then
        curl -f --retry 3 --max-time 10 -o /tmp/slave.jar "${SLAVE_JAR_URL}"
    elif which wget >/dev/null; then
        wget -O /tmp/slave.jar --timeout 10 "${SLAVE_JAR_URL}"
    else
        echo Unable to find curl or wget to download slave jar. 1>&2
        exit 1
    fi
fi

JAVA_HOME=${JDK_HOME:-${JAVA_HOME:-}}
if [ -z "$JAVA_HOME" ]; then
    JAVA_BIN=`which java`
else
    JAVA_BIN="${JAVA_HOME}/bin/java"
fi

if [ -z "$JAVA_BIN" ]; then
    echo Unable to find java executable: JDK_HOME, JAVA_HOME, PATH 1>&2
    exit 2000
fi

"$JAVA_BIN" -jar /tmp/slave.jar