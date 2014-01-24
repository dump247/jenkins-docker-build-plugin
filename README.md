jenkins-docker-build-plugin
===========================

Run Jenkins jobs inside of docker containers.

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
