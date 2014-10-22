package net.dump247.jenkins.plugins.dockerbuild;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
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
import net.dump247.jenkins.plugins.dockerbuild.log.Logger;
import org.apache.commons.lang.mutable.MutableInt;

import java.io.IOException;
import java.io.PrintStream;
import java.util.List;

import static com.google.common.collect.Lists.newArrayListWithCapacity;
import static java.lang.String.format;

public class DockerComputerLauncher extends ComputerLauncher {
    private static final Logger LOG = Logger.get(DockerComputerLauncher.class);

    /**
     * Bash script that launches the slave jar *
     */
    private static final String SLAVE_SCRIPT = "" +
            "SLAVE_JAR_PATH=\"%s\"\n" +
            "SLAVE_JAR_URL=\"%s\"\n" +
            "\n" +
            "if [ -f \"$SLAVE_JAR_PATH\" ]; then\n" +
            "    cp -f \"$SLAVE_JAR_PATH\" /tmp/slave.jar\n" +
            "else\n" +
            "    if which curl; then\n" +
            "        curl -f --retry 3 -o /tmp/slave.jar \"$SLAVE_JAR_URL\" || exit 1000\n" +
            "    elif which wget; then\n" +
            "        wget -O /tmp/slave.jar \"$SLAVE_JAR_URL\" || exit 1000" +
            "    else\n" +
            "        echo No local slave jar. Unable to find curl/wget to download slave jar. 1>&2\n" +
            "        exit 1000\n" +
            "    fi\n" +
            "fi\n" +
            "\n" +
            "JAVA_HOME=${JDK_HOME:-$JAVA_HOME}\n" +
            "if [ -z \"$JAVA_HOME\" ]; then\n" +
            "    JAVA_BIN=`which java`\n" +
            "else\n" +
            "    JAVA_BIN=$JAVA_HOME/bin/java\n" +
            "fi\n" +
            "\n" +
            "if [ -z \"$JAVA_BIN\" ]; then\n" +
            "    echo Unable to find java executable: JDK_HOME, JAVA_HOME, PATH 1>&2\n" +
            "    exit 2000\n" +
            "fi\n" +
            "\n" +
            "\"$JAVA_BIN\" -jar /tmp/slave.jar";

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

        LOG.debug("Attaching container streams: containerId={0}", containerId);
        final DockerClient.ContainerStreams streams = _dockerClient.attachContainerStreams(containerId);

        LOG.debug("Starting container: containerId={0}", containerId);
        _dockerClient.startContainer(new StartContainerRequest()
                .withContainerId(containerId)
                .withBindings(ImmutableList.copyOf(_directoryBindings)));

        final StreamCopyThread stderrThread = new StreamCopyThread(containerId + " stderr", streams.stderr, listener.getLogger());
        stderrThread.start();

        computer.setChannel(streams.stdout, streams.stdin, listener, new Channel.Listener() {
            @Override
            public void onClosed(final Channel channel, final IOException cause) {
                try {
                    LOG.debug("Closing container stdin: containerId={0}", containerId);
                    streams.stdin.close();
                } catch (Throwable ex) {
                    LOG.debug("Error closing container stdin: containerId={0}", containerId, ex);
                }

                try {
                    LOG.debug("Closing container stdout: containerId={0}", containerId);
                    streams.stdout.close();
                } catch (Throwable ex) {
                    LOG.debug("Error closing container stdout: containerId={0}", containerId, ex);
                }

                try {
                    LOG.debug("Closing container stderr: containerId={0}", containerId);
                    stderrThread.join(2000);
                    stderrThread.interrupt();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }

                try {
                    LOG.debug("Stopping container: containerId={0}", containerId);
                    _dockerClient.stopContainer(containerId);
                } catch (ContainerNotFoundException ex) {
                    LOG.debug("Container not found: containerId={0}", containerId, ex);
                } catch (DockerException ex) {
                    LOG.debug("Error stopping container: containerId={0}", containerId, ex);
                    ex.printStackTrace(listener.error("Error stopping removing container: [containerId=%s]", containerId));
                }

                try {
                    LOG.debug("Removing container: containerId={0}", containerId);
                    _dockerClient.removeContainer(containerId);
                } catch (ContainerNotFoundException ex) {
                    LOG.debug("Container not found: containerId={0}", containerId, ex);
                } catch (DockerException ex) {
                    LOG.warn("Error removing container: containerId={0}", containerId, ex);
                    ex.printStackTrace(listener.error("Error removing removing container: [containerId=%s]", containerId));
                }
            }
        });
    }

    private String createContainer(final TaskListener listener) throws IOException {
        LOG.debug("Creating container: image={0} endpoint={1}", _imageName, _dockerClient);

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
                .withCommand("/bin/bash", "-c", format(SLAVE_SCRIPT, _slaveJarPath.or(""), slaveJarUrl)));

        for (String warning : response.getWarnings()) {
            LOG.warn("Warning from docker creating container: image={0}, containerId={1}, message={2}", _imageName, response.getContainerId(), warning);
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
                    LOG.info("Pulling docker image: image={0}, host={1}", _imageName, _dockerClient.getEndpoint());
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
