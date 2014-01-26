package net.dump247.jenkins.plugins.dockerbuild;

import hudson.util.StreamCopyThread;
import net.dump247.docker.AttachResponse;
import net.dump247.docker.ContainerVolume;
import net.dump247.docker.CreateContainerRequest;
import net.dump247.docker.CreateContainerResponse;
import net.dump247.docker.DirectoryBinding;
import net.dump247.docker.DockerClient;
import net.dump247.docker.DockerException;
import net.dump247.docker.StartContainerRequest;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.lang.String.format;

/** Runs a command in a docker container. */
public class DockerRunner implements Callable<Integer> {
    private static final Logger LOG = Logger.getLogger(DockerRunner.class.getName());

    private final String _image;
    private final List<String> _command;

    private Map<String, String> _environment = Collections.emptyMap();
    private String _workingDirectory = "";
    private DockerClient _dockerClient;
    private OutputStream _stdout = NullOutputStream.INSTANCE;
    private OutputStream _stderr = NullOutputStream.INSTANCE;
    private List<DirectoryBinding> _directoryBindings = Collections.emptyList();

    /**
     * Initialize a new instance.
     *
     * @param image   docker image to run the command with
     * @param command command to run
     */
    public DockerRunner(String image, List<String> command) {
        if (image == null) {
            throw new NullPointerException("image");
        }

        if (command == null) {
            throw new NullPointerException("command");
        }

        _image = image;
        _command = Collections.unmodifiableList(new ArrayList<String>(command));
    }

    public Integer call() throws Exception {
        String containerId = createContainer();
        StreamCopyThread stdOutCopy = null;
        StreamCopyThread stdErrCopy = null;

        try {
            LOG.fine(format("Running job command in container: [container=%s]", containerId));

            LOG.fine("Starting container...");
            getDockerClient().startContainer(new StartContainerRequest()
                    .withContainerId(containerId)
                    .withBindings(_directoryBindings));

            LOG.fine("Attaching to container stdin/stdout...");
            AttachResponse attachResponse = getDockerClient().attachContainer(containerId);

            stdOutCopy = new StreamCopyThread("stdout copier", attachResponse.getStdout(), _stdout);
            stdOutCopy.start();

            stdErrCopy = new StreamCopyThread("stderr copier", attachResponse.getStderr(), _stderr);
            stdErrCopy.start();

            LOG.fine("Waiting for container...");
            int statusCode = getDockerClient().waitContainer(containerId).getStatusCode();

            LOG.fine(format("Command completed: [status=%d]", statusCode));
            return statusCode;
        } finally {
            if (stdOutCopy != null) {
                stdOutCopy.join(2 * 1000);
                stdOutCopy.interrupt();
            }

            if (stdErrCopy != null) {
                stdErrCopy.join(100);
                stdErrCopy.interrupt();
            }

            try {
                // Best effort cleanup. Removing the container is not
                // required to complete the job. The containers should be very small
                // so there is only small risk if they fail to get deleted.
                LOG.fine(format("Removing job container: [container=%s]", containerId));
                getDockerClient().removeContainer(containerId);
            } catch (Exception ex) {
                LOG.log(Level.WARNING, format("Error removing container: [container=%s]", containerId), ex);
            }
        }
    }

    /**
     * Get the environment variables that will be set in the container.
     *
     * @return map from variable name to value
     */
    public Map<String, String> getEnvironment() {
        return _environment;
    }

    /**
     * Set the environment variables that will be set in the container.
     *
     * @param environment map from variable name to value
     */
    public void setEnvironment(final Map<String, String> environment) {
        if (environment == null) {
            throw new NullPointerException("environment");
        }

        _environment = Collections.unmodifiableMap(new HashMap<String, String>(environment));
    }

    /**
     * Get the working directory for the command.
     *
     * @return working directory path or empty string to use default
     */
    public String getWorkingDirectory() {
        return _workingDirectory;
    }

    /**
     * Set the working directory for the command.
     * <p/>
     * An empty string makes the command use the home directory of the
     * container user that the command is running as. Default value is an
     * empty string.
     *
     * @param workingDirectory working directory path
     */
    public void setWorkingDirectory(final String workingDirectory) {
        if (workingDirectory == null) {
            throw new NullPointerException("workingDirectory");
        }

        _workingDirectory = workingDirectory;
    }

    /**
     * Get the docker client to issue the command with.
     *
     * @return docker client
     */
    public DockerClient getDockerClient() {
        if (_dockerClient == null) {
            _dockerClient = DockerClient.localClient();
        }

        return _dockerClient;
    }

    /**
     * Set the docker client to issue the command with.
     * <p/>
     * Default is a local docker client (see {@link net.dump247.docker.DockerClient#localClient()}).
     *
     * @param dockerClient docker client
     */
    public void setDockerClient(final DockerClient dockerClient) {
        if (dockerClient == null) {
            throw new NullPointerException("dockerClient");
        }

        _dockerClient = dockerClient;
    }

    /**
     * Get the stream to copy command standard output to.
     *
     * @return stream to copy standard output to
     */
    public OutputStream getStdout() {
        return _stdout;
    }

    /**
     * Set the stream to copy standard output to.
     * <p/>
     * Default is {@link NullOutputStream#INSTANCE}.
     *
     * @param stdout stream to copy stdout to
     */
    public void setStdout(final OutputStream stdout) {
        if (stdout == null) {
            throw new NullPointerException("stdout");
        }

        _stdout = stdout;
    }

    /**
     * Get the stream to copy command standard error to.
     *
     * @return stream to copy standard error to
     */
    public OutputStream getStderr() {
        return _stderr;
    }

    /**
     * Set the stream to copy standard error to.
     * <p/>
     * Default is {@link NullOutputStream#INSTANCE}.
     *
     * @param stderr stream to copy stderr to
     */
    public void setStderr(final OutputStream stderr) {
        if (stderr == null) {
            throw new NullPointerException("stderr");
        }

        _stderr = stderr;
    }

    /**
     * Get the image to run the command with.
     *
     * @return project image name
     */
    public String getImage() {
        return _image;
    }

    /**
     * Get the command to run.
     *
     * @return command to run in the container
     */
    public List<String> getCommand() {
        return _command;
    }

    /**
     * Get the bindings from host filesystem to container filesystem.
     *
     * @return directory bindings
     */
    public List<DirectoryBinding> getDirectoryBindings() {
        return _directoryBindings;
    }

    /**
     * Set the bindings from host filesystem to container filesystem.
     * <p/>
     * Default is empty list.
     *
     * @param directoryBindings directory bindings
     */
    public void setDirectoryBindings(final List<DirectoryBinding> directoryBindings) {
        if (directoryBindings == null) {
            throw new NullPointerException("directoryBindings");
        }

        _directoryBindings = Collections.unmodifiableList(new ArrayList<DirectoryBinding>(directoryBindings));
    }

    private String createContainer() throws DockerException {
        LOG.fine(format("Creating container: [image=%s]", getImage()));

        CreateContainerRequest createContainerRequest = new CreateContainerRequest()
                .withImage(_image)
                .withCommand(_command)
                .withWorkingDir(_workingDirectory)
                .withEnvironment(_environment)
                .withAttachStderr(true)
                .withAttachStdout(true);

        if (_directoryBindings.size() > 0) {
            List<ContainerVolume> volumes = new ArrayList<ContainerVolume>(_directoryBindings.size());

            for (DirectoryBinding binding : _directoryBindings) {
                volumes.add(new ContainerVolume(binding.getContainerPath()));
            }

            createContainerRequest.setVolumes(volumes);
        }

        CreateContainerResponse response = getDockerClient().createContainer(createContainerRequest);

        for (String warning : response.getWarnings()) {
            LOG.warning(format("%s: [image=%s]", warning, createContainerRequest.getImage()));
        }

        return response.getContainerId();
    }
}
