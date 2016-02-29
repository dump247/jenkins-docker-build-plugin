#!/usr/bin/env bash

set -o errexit # Exit if any command fials
set -o nounset # Don't reference variables that have not been set

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )

[ -f "${DIR}/init_slave.sh" ] && /bin/bash "${DIR}/init_slave.sh"

SLAVE_JAR_PATH="${DIR}/slave.jar"

source "${DIR}/properties.sh"

if [[ -f ${SLAVE_JAR_PATH} ]]; then
    cp -f "${SLAVE_JAR_PATH}" /tmp/slave.jar
else
    echo "Slave jar not found: ${SLAVE_JAR_PATH}" 1>&2
    exit 1
fi

JAVA_HOME=${JDK_HOME:-${JAVA_HOME:-}}
if [[ -z ${JAVA_HOME} ]]; then
    JAVA_BIN=$(which java) || :
else
    JAVA_BIN="${JAVA_HOME}/bin/java"
fi

if [[ -z ${JAVA_BIN} ]]; then
    echo "Unable to find java executable: JDK_HOME, JAVA_HOME, PATH" 1>&2
    exit 1
fi

"${JAVA_BIN}" -jar /tmp/slave.jar -connectTo "${CONNECT_ADDRESS}:${CONNECT_PORT}"