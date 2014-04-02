jenkins-docker-build-plugin
===========================

Run Jenkins jobs inside of docker containers.

Setup Slave Host
================

The plugin requires one or more machines with docker. These machines will be
responsible for creating containers that the jobs are run in. For test purposes,
the master can also be used to run jobs.

These instructions are for ubuntu, but can be easily adapted to other platforms.

1. Install docker
  * See http://docs.docker.io/en/latest/installation/ubuntulinux/
  * Note that docker 0.9 can be unstable, so try 0.8.1 if you are experiencing
    problems
2. Setup HTTP API
  * Add `DOCKER_OPTS="-H tcp://0.0.0.0:4243 -H unix:///var/run/docker.sock"` to `/etc/default/docker`
3. Create slave directory
  1. `mkdir /usr/lib/jenkins`
  3. Install dependencies: `sudo apt-get install -y openjdk-7-jre-headless curl`
  2. Copy the slave jar from your master `curl -o /usr/lib/jenkins http://master:port/jnlpJars/slave.jar`
    * This should be done each time jenkins is updated. A cron job might be a good idea.
  4. Copy JRE to slave dir: `cp -r /usr/lib/jvm/java-7-openjdk-amd64/jre/ /usr/lib/jenkins/jre/`

Docker API Integration Tests
============================

## Start Docker API

This only necessary once. Must have vagrant installed.

This must be run in the project directory (i.e. the directory that contains
this README file).

```bash
wget https://github.com/dotcloud/docker/blob/master/Dockerfile
vagrant up
# Wait (you may have to enter password and/or restart vm)
vagrant ssh --command 'sudo echo DOCKER_OPTS="-H tcp://0.0.0.0:49000" >> /etc/default/docker'
vagrant ssh --command 'sudo service docker restart'
```

## Run Integration Tests

```bash
mvn verify -Pintegration
```
