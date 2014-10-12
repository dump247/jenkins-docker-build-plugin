jenkins-docker-build-plugin
===========================

Run Jenkins jobs inside of docker containers.

Given an image for a job, the plugin will start a new container with the Jenkins
agent, run the job, then stop and delete the container. The image can be set with
a configuration option in the job itself or using job labels to map the job to a
common image. The plugin supports encryption and authentication when communicating
with the docker API.

See the [wiki](https://github.com/dump247/jenkins-docker-build-plugin/wiki) for more
information on setting up and configuring the plugin and docker.

# License

The MIT License (MIT) Copyright (c) 2014 Cory Thomas

See [LICENSE](LICENSE)
