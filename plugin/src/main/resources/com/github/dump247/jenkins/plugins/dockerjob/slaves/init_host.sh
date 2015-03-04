#!/bin/bash

#
# This script is run after the plugin successfully connects to a job host. The script verifies that
# the necessary requirements are available and initializes the machine.
#
# See SlaveClient#initialize()
#

set -o errexit
set -o nounset

# Verify requirements
which docker python3 >/dev/null

python3 -c '
import docker
cli = docker.Client(version="1.15")
for key, value in cli.version().items():
  print("{}: {}".format(key, value))
'

LAUNCH_DIR=/var/lib/jenkins-docker

# Clean the launch directory
rm -rf ${LAUNCH_DIR}/* >/dev/null
mkdir -p ${LAUNCH_DIR}/slave >/dev/null

# Discover IP address of docker0 interface
# The specific port number value is irrelevant so long as the port is available
cat >${LAUNCH_DIR}/slave/properties.sh <<EOF
CONNECT_ADDRESS=$(/sbin/ip addr show docker0 | grep -o 'inet [0-9]\+\.[0-9]\+\.[0-9]\+\.[0-9]\+' | grep -o [0-9].*)
CONNECT_PORT=12112
EOF
