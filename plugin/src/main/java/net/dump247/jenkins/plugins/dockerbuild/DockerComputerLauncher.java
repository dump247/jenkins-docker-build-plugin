package net.dump247.jenkins.plugins.dockerbuild;

import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.io.CharStreams;
import com.google.common.io.InputSupplier;
import hudson.Extension;
import hudson.model.TaskListener;
import hudson.remoting.Channel;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.SlaveComputer;
import hudson.util.StreamCopyThread;
import jenkins.model.Jenkins;
import net.dump247.docker.ContainerNotFoundException;
import net.dump247.docker.ContainerVolume;
import net.dump247.docker.CreateContainerRequest;
import net.dump247.docker.CreateContainerResponse;
import net.dump247.docker.DirectoryBinding;
import net.dump247.docker.DockerClient;
import net.dump247.docker.DockerException;
import net.dump247.docker.ProgressEvent;
import net.dump247.docker.ProgressListener;
import net.dump247.docker.StartContainerRequest;
import org.apache.commons.lang.mutable.MutableInt;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.google.common.collect.Lists.newArrayListWithCapacity;
import static java.lang.String.format;

public class DockerComputerLauncher extends ComputerLauncher {
    private static final Logger LOG = Logger.getLogger(DockerComputerLauncher.class.getName());

    /**
     * Bash script that launches the slave jar *
     */
    private static final String SLAVE_SCRIPT;

    static {
        try {
            SLAVE_SCRIPT = CharStreams.toString(new InputSupplier<InputStreamReader>() {
                @Override
                public InputStreamReader getInput() throws IOException {
                    return new InputStreamReader(DockerComputerLauncher.class.getResourceAsStream("slave_launch.sh"), Charsets.US_ASCII);
                }
            });
        } catch (IOException ex) {
            throw Throwables.propagate(ex);
        }
    }

    private final DockerClient _dockerClient;
    private final String _imageName;
    private final List<DirectoryBinding> _directoryBindings;
    private final Optional<String> _slaveJarPath;

    public DockerComputerLauncher(DockerClient dockerClient, String imageName, List<DirectoryBinding> directoryBindings, Optional<String> slaveJarPath) {
        _dockerClient = dockerClient;
        _imageName = imageName;
        _directoryBindings = directoryBindings;
        _slaveJarPath = slaveJarPath;
    }

    public DockerClient getDockerClient() {
        return _dockerClient;
    }

    @Override
    public void launch(final SlaveComputer computer, final TaskListener listener) throws IOException, InterruptedException {
        pullImage(listener);
        listener.getLogger().println(format("### Running job with Docker image %s", _imageName));
        final String containerId = createContainer(listener);

        LOG.fine(format("Attaching container streams: containerId=%s", containerId));
        final DockerClient.ContainerStreams streams = _dockerClient.attachContainerStreams(containerId);

        LOG.fine(format("Starting container: containerId=%s", containerId));
        _dockerClient.startContainer(new StartContainerRequest()
                .withContainerId(containerId)
                .withBindings(ImmutableList.copyOf(_directoryBindings)));

        final StreamCopyThread stderrThread = new StreamCopyThread(containerId + " stderr", streams.stderr, listener.getLogger());
        stderrThread.start();

        computer.setChannel(streams.stdout, streams.stdin, listener, new Channel.Listener() {
            @Override
            public void onClosed(final Channel channel, final IOException cause) {
                try {
                    LOG.fine(format("Closing container stdin: containerId=%s", containerId));
                    streams.stdin.close();
                } catch (Throwable ex) {
                    LOG.log(Level.FINE, format("Error closing container stdin: containerId=%s", containerId), ex);
                }

                try {
                    LOG.fine(format("Closing container stdout: containerId=%s", containerId));
                    streams.stdout.close();
                } catch (Throwable ex) {
                    LOG.log(Level.FINE, format("Error closing container stdout: containerId=%s", containerId), ex);
                }

                try {
                    LOG.fine(format("Closing container stderr: containerId=%s", containerId));
                    stderrThread.join(2000);
                    stderrThread.interrupt();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }

                try {
                    LOG.fine(format("Stopping container: containerId=%s", containerId));
                    _dockerClient.stopContainer(containerId);
                } catch (ContainerNotFoundException ex) {
                    LOG.log(Level.FINE, format("Container not found: containerId=%s", containerId), ex);
                } catch (DockerException ex) {
                    LOG.log(Level.FINE, format("Error stopping container: containerId=%s", containerId), ex);
                    ex.printStackTrace(listener.error("Error stopping removing container: [containerId=%s]", containerId));
                }

                try {
                    LOG.fine(format("Removing container: containerId=%s", containerId));
                    _dockerClient.removeContainer(containerId);
                } catch (ContainerNotFoundException ex) {
                    LOG.log(Level.FINE, format("Container not found: containerId=%s", containerId), ex);
                } catch (DockerException ex) {
                    LOG.log(Level.WARNING, format("Error removing container: containerId=%s", containerId), ex);
                    ex.printStackTrace(listener.error("Error removing removing container: [containerId=%s]", containerId));
                }
            }
        });
    }

    private String createContainer(final TaskListener listener) throws IOException {
        LOG.fine(format("Creating container: image=%s endpoint=%s", _imageName, _dockerClient.getEndpoint()));

        List<ContainerVolume> volumes = newArrayListWithCapacity(_directoryBindings.size());

        for (DirectoryBinding binding : _directoryBindings) {
            volumes.add(new ContainerVolume(binding.getContainerPath()));
        }

        String slaveJarUrl = Jenkins.getInstance().getRootUrl() + "/jnlpJars/slave.jar";

        CreateContainerResponse response = _dockerClient.createContainer(new CreateContainerRequest()
                .withImage(_imageName)
                .withAttachStderr(true)
                .withAttachStdin(true)
                .withAttachStdout(true)
                .withStdinOnce(true)
                .withOpenStdin(true)
                .withTty(false)
                .withVolumes(volumes)
                .withCommand("/bin/bash", "-l", "-c", format(SLAVE_SCRIPT, _slaveJarPath.or(""), slaveJarUrl)));

        for (String warning : response.getWarnings()) {
            LOG.warning(format("Warning from docker creating container: image=%s, containerId=%s, message=%s", _imageName, response.getContainerId(), warning));
            listener.getLogger().println(format("DOCKER WARN: %s", warning));
        }

        return response.getContainerId();
    }

    private void pullImage(final TaskListener listener) throws DockerException {
        final PrintStream logger = listener.getLogger();
        final MutableInt counter = new MutableInt();

        _dockerClient.pullImage(_imageName, new ProgressListener() {
            public void progress(final ProgressEvent event) {
                // Only print out progress or error messages
                if (event.getCode() == ProgressEvent.Code.Ok && event.getTotal() == 0) {
                    return;
                }

                if (counter.intValue() == 0) {
                    LOG.info(format("Pulling docker image: image=%s, host=%s", _imageName, _dockerClient.getEndpoint()));
                    logger.println(format("### Pulling Docker image %s", _imageName));
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
                    logger.println(message);
                } else {
                    listener.error(message.toString());
                }
            }
        });

        if (counter.intValue() > 0) {
            logger.println(format("### Done pulling Docker image %s", _imageName));
        }
    }

    @Extension
    public static class Descriptor extends hudson.model.Descriptor<ComputerLauncher> {
        @Override
        public String getDisplayName() {
            return "Docker Computer Launcher";
        }
    }
}
