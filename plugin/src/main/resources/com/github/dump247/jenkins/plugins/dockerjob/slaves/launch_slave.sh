#!/usr/bin/env bash

set -o errexit # Exit if any command fials
set -o nounset # Don't reference variables that have not been set

function create_user {
    local user=${1}
    local uid=${2}
    local group=${3}
    local home=${4}

    local info=$(getent passwd ${uid}) || :
    if [[ $? -eq 0 ]]; then
        user=$(echo "${info}" | cut -d: -f1)
    else
        useradd --system                            \
                --uid ${uid}                        \
                --gid ${group}                      \
                --home ${home}                      \
                --shell /sbin/nologin               \
                --comment 'Jenkins Slave'           \
                ${user}
    fi

    echo ${user}
}

function create_group {
    local group=${1}
    local gid=${2}

    local info=$(getent group ${gid}) || :
    if [[ $? -eq 0 ]]; then
        group=$(echo "${info}" | cut -d: -f1)
    else
        groupadd --system                           \
                 --gid ${gid}                       \
                 ${group}
    fi

    echo ${group}
}

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )

JENKINS_GID=5000
JENKINS_GROUP=jenkins
JENKINS_UID=5000
JENKINS_USER=jenkins
JENKINS_HOME=/var/lib/jenkins

JENKINS_GROUP=$(create_group ${JENKINS_GROUP} ${JENKINS_GID})
JENKINS_USER=$(create_user ${JENKINS_USER} ${JENKINS_UID} ${JENKINS_GROUP} ${JENKINS_HOME})

[ -d ${JENKINS_HOME} ] || mkdir --parents ${JENKINS_HOME}
chown --recursive ${JENKINS_USER}:${JENKINS_GROUP} ${JENKINS_HOME}

[ -f "${DIR}/init_slave.sh" ] && /bin/bash "${DIR}/init_slave.sh"

SLAVE_JAR_PATH="${DIR}/slave.jar"

source "${DIR}/properties.sh"

if [ -f "${SLAVE_JAR_PATH}" ]; then
    cp -f "${SLAVE_JAR_PATH}" /tmp/slave.jar
else
    echo "Slave jar not found: ${SLAVE_JAR_PATH}" 1>&2
    exit 1
fi

JAVA_HOME=${JDK_HOME:-${JAVA_HOME:-}}
if [ -z "${JAVA_HOME}" ]; then
    JAVA_BIN=$(which java) || :
else
    JAVA_BIN="${JAVA_HOME}/bin/java"
fi

if [ -z "${JAVA_BIN}" ]; then
    echo "Unable to find java executable: JDK_HOME, JAVA_HOME, PATH" 1>&2
    exit 1
fi

runuser ${JENKINS_USER}      \
        --command "'${JAVA_BIN}' -jar /tmp/slave.jar -connectTo '${CONNECT_ADDRESS}:${CONNECT_PORT}'"