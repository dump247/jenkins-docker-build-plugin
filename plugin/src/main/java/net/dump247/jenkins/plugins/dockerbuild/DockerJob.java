package net.dump247.jenkins.plugins.dockerbuild;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import net.dump247.docker.ContainerVolume;
import net.dump247.docker.CreateContainerRequest;
import net.dump247.docker.CreateContainerResponse;
import net.dump247.docker.DirectoryBinding;
import net.dump247.docker.DockerClient;
import net.dump247.docker.DockerException;
import net.dump247.docker.ImageName;
import net.dump247.docker.ImageNotFoundException;
import net.dump247.docker.InspectImageResponse;
import net.dump247.docker.ProgressEvent;
import net.dump247.docker.ProgressListener;
import net.dump247.docker.StartContainerRequest;
import org.apache.commons.lang.mutable.MutableInt;

import java.util.List;
import java.util.Objects;
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
    private final Optional<ImageName> _commitImage;
    private final List<DirectoryBinding> _directoryMappings;
    private final List<String> _launchCommand;

    public DockerJob(DockerClient dockerClient, ImageName jobImage, List<String> launchCommand, Optional<ImageName> commitImage, List<DirectoryBinding> directoryMappings) {
        _dockerClient = checkNotNull(dockerClient);
        _jobImage = checkNotNull(jobImage);
        _launchCommand = ImmutableList.copyOf(launchCommand);
        _commitImage = checkNotNull(commitImage);
        _directoryMappings = ImmutableList.copyOf(directoryMappings);

        checkArgument(launchCommand.size() > 0, "launchCommand can not be empty");
    }

    public DockerClient getDockerClient() {
        return _dockerClient;
    }

    public DockerJobContainer start(Listener listener) {
        ImageName containerImage = _jobImage;

        try {
            pullImage(_jobImage.toString(), listener);

            if (_commitImage.isPresent()) {
                InspectImageResponse jobImageDetails = _dockerClient.inspectImage(_jobImage.toString());

                try {
                    InspectImageResponse taskImageDetails = _dockerClient.inspectImage(_commitImage.get().toString());

                    if (Objects.equals(taskImageDetails.getParentId(), jobImageDetails.getId())) {
                        containerImage = _commitImage.get();
                    }
                } catch (ImageNotFoundException ex) {
                    LOG.log(Level.FINE, format("Task image not found, will be created when container exits: [image=%s]", _commitImage.get()), ex);
                }
            }

            String containerId = createContainer(containerImage.toString(), listener);
            DockerClient.ContainerStreams streams = _dockerClient.attachContainerStreams(containerId);
            _dockerClient.startContainer(new StartContainerRequest()
                    .withContainerId(containerId)
                    .withBindings(_directoryMappings));

            return new DockerJobContainer(_dockerClient, containerId, streams, _commitImage);
        } catch (Exception ex) {
            throw new RuntimeException(format("Error starting job container: [image=%s] [endpoint=%s]", containerImage, _dockerClient.getEndpoint()), ex);
        }
    }

    private String createContainer(String imageName, Listener listener) throws DockerException {
        List<ContainerVolume> volumes = newArrayListWithCapacity(_directoryMappings.size());

        for (DirectoryBinding binding : _directoryMappings) {
            volumes.add(new ContainerVolume(binding.getContainerPath()));
        }

        CreateContainerResponse response = _dockerClient.createContainer(new CreateContainerRequest()
                .withImage(imageName)
                .withAttachStderr(true)
                .withAttachStdin(true)
                .withAttachStdout(true)
                .withStdinOnce(true)
                .withOpenStdin(true)
                .withTty(false)
                .withVolumes(volumes)
                .withCommand(_launchCommand));

        for (String warning : response.getWarnings()) {
            LOG.warning(format("Warning from docker creating container: [image=%s] [containerId=%s] [message=%s]", imageName, response.getContainerId(), warning));
            listener.warn("DOCKER (%s/%s): %s", imageName, response.getContainerId(), warning);
        }

        return response.getContainerId();
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