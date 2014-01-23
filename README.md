jenkins-docker-build-plugin
===========================

Run Jenkins jobs inside of docker containers.

Docker API Integration Tests
============================

## Start Docker API

This only necessary once and can be run in any directory. Must have vagrant installed.

```bash
wget https://github.com/dotcloud/docker/blob/master/Dockerfile
vagrant up
# Wait (you may have to enter password and/or restart vm)
vagrant ssh --command 'sudo echo DOCKER_OPTS="-H tcp://0.0.0.0:49000" >> /etc/default/docker'
vagrant ssh --command 'sudo service docker restart'
```

## Run Integration Tests

```bash
cd docker-api/
mvn verify -Pintegration
```
