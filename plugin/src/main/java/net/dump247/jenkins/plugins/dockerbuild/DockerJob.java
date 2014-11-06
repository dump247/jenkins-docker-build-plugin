package net.dump247.jenkins.plugins.dockerbuild;

import com.google.common.collect.ImmutableList;
import net.dump247.docker.ContainerNotFoundException;
import net.dump247.docker.ContainerVolume;
import net.dump247.docker.CreateContainerRequest;
import net.dump247.docker.CreateContainerResponse;
import net.dump247.docker.DirectoryBinding;
import net.dump247.docker.DockerClient;
import net.dump247.docker.DockerException;
import net.dump247.docker.ImageName;
import net.dump247.docker.InspectContainerResponse;
import net.dump247.docker.InspectImageResponse;
import net.dump247.docker.ProgressEvent;
import net.dump247.docker.ProgressListener;
import net.dump247.docker.StartContainerRequest;
import org.apache.commons.lang.mutable.MutableInt;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Lists.newArrayListWithCapacity;
import static java.lang.String.format;

/**
 * Sets up and tears down a docker container for a jenkins job.
 */
public class DockerJob {
    private static final Logger LOG = Logger.getLogger(DockerJob.class.getName());

    private final DockerClient _dockerClient;
    private final ImageName _jobImage;
    private final boolean _resetJob;
    private final String _jobName;
    private final List<DirectoryBinding> _directoryMappings;
    private final List<String> _launchCommand;

    private final String _jobContainerName;

    public DockerJob(DockerClient dockerClient, String jobName, ImageName jobImage, List<String> launchCommand, boolean resetJob, List<DirectoryBinding> directoryMappings) {
        _dockerClient = checkNotNull(dockerClient);
        _jobName = checkNotNull(jobName);
        _jobImage = checkNotNull(jobImage);
        _launchCommand = ImmutableList.copyOf(launchCommand);
        _resetJob = resetJob;
        _directoryMappings = ImmutableList.copyOf(directoryMappings);

        checkArgument(launchCommand.size() > 0, "launchCommand can not be empty");

        _jobContainerName = CreateContainerRequest.encodeName(_jobName);
    }

    public DockerClient getDockerClient() {
        return _dockerClient;
    }

    public DockerJobContainer start(Listener listener) {
        ImageName containerImage = _jobImage;

        try {
            pullImage(_jobImage.toString(), listener);
            String containerId = createContainer(listener);
            DockerClient.ContainerStreams streams = _dockerClient.attachContainerStreams(containerId);
            _dockerClient.startContainer(new StartContainerRequest()
                    .withContainerId(containerId)
                    .withBindings(_directoryMappings));

            return new DockerJobContainer(_dockerClient, containerId, streams, _resetJob);
        } catch (Exception ex) {
            throw new RuntimeException(format("Error starting job container: [image=%s] [endpoint=%s]", containerImage, _dockerClient.getEndpoint()), ex);
        }
    }

    private String createContainer(Listener listener) throws DockerException {
        CreateContainerRequest containerRequest = buildContainerRequest();

        try {
            LOG.fine(format("Inspecting container %s", _jobContainerName));
            InspectContainerResponse containerInfo = _dockerClient.inspectContainer(_jobContainerName);

            if (!_resetJob) {
                if (optionsEqual(containerInfo.getConfig(), containerRequest)) {
                    InspectImageResponse jobImageDetails = _dockerClient.inspectImage(_jobImage.toString());

                    LOG.fine(format("Options are equal! jobImage=%s containerImage=%s", jobImageDetails.getId(), containerInfo.getImageId()));
                    if (jobImageDetails.getId().equals(containerInfo.getImageId())) {
                        return containerInfo.getId();
                    }
                }
            }

            LOG.fine(format("Deleting old container: %s", containerInfo.getId()));
            _dockerClient.removeContainer(containerInfo.getId());
        } catch (ContainerNotFoundException ex) {
            LOG.log(Level.FINE, format("Job container not found. Will create new container: [containerName=%s]", _jobContainerName), ex);
        }

        CreateContainerResponse response = _dockerClient.createContainer(containerRequest);

        for (String warning : response.getWarnings()) {
            LOG.warning(format("Warning from docker creating container: [image=%s] [containerId=%s] [message=%s]", _jobImage, response.getContainerId(), warning));
            listener.warn("DOCKER (%s/%s): %s", _jobImage, response.getContainerId(), warning);
        }

        return response.getContainerId();
    }

    private boolean optionsEqual(InspectContainerResponse.ContainerConfig config, CreateContainerRequest containerRequest) {
        return config.isAttachStderr() == containerRequest.isAttachStderr() &&
                config.isAttachStdin() == containerRequest.isAttachStdin() &&
                config.isAttachStdout() == containerRequest.isAttachStdout() &&
                config.isOpenStdin() == containerRequest.isOpenStdin() &&
                config.isStdinOnce() == containerRequest.isStdinOnce() &&
                config.isTty() == containerRequest.isTty() &&
                config.getCommand().equals(containerRequest.getCommand()) &&
                config.getVolumes().equals(containerRequest.getVolumes());
    }

    private CreateContainerRequest buildContainerRequest() {
        List<ContainerVolume> volumes = newArrayListWithCapacity(_directoryMappings.size());

        for (DirectoryBinding binding : _directoryMappings) {
            volumes.add(new ContainerVolume(binding.getContainerPath()));
        }

        // NOTE if you add new options to the container, be sure to update #optionsEqual
        return new CreateContainerRequest()
                .withName(_jobContainerName)
                .withImage(_jobImage.toString())
                .withAttachStderr(true)
                .withAttachStdin(true)
                .withAttachStdout(true)
                .withStdinOnce(true)
                .withOpenStdin(true)
                .withTty(false)
                .withVolumes(volumes)
                .withCommand(_launchCommand);
    }

    private void pullImage(final String imageName, final Listener listener) throws DockerException {
        final MutableInt counter = new MutableInt();

        _dockerClient.pullImage(imageName, new ProgressListener() {
            @Override
            public void progress(ProgressEvent event) {
                // Only print out progress or error messages
                if (event.getCode() == ProgressEvent.Code.Ok && event.getTotal() == 0) {
                    return;
                }

                if (counter.intValue() == 0) {
                    LOG.info(format("Pulling docker image: image=%s, host=%s", imageName, _dockerClient));
                    listener.info("### Pulling Docker image %s", imageName);
                }

                counter.increment();

                String statusMessage = event.getStatusMessage();
                String detailMessage = event.getDetailMessage();

                StringBuilder message = new StringBuilder(statusMessage.length() + 2 + detailMessage.length());
                message.append(statusMessage);

                if (detailMessage.length() > 0) {
                    message.append(": ").append(detailMessage);
                }

                if (event.getCode() == ProgressEvent.Code.Ok) {
                    listener.info(message.toString());
                } else {
                    listener.error(message.toString());
                }
            }
        });

        if (counter.intValue() > 0) {
            listener.info("### Done pulling Docker image %s", imageName);
        }
    }

    public static interface Listener {
        void info(String format, Object... args);

        void warn(String format, Object... args);

        void error(String format, Object... args);
    }
}