package net.dump247.jenkins.plugins.dockerbuild;

import net.dump247.docker.ContainerNotFoundException;
import net.dump247.docker.DockerClient;
import net.dump247.docker.DockerException;

import java.io.Closeable;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.String.format;

public class DockerJobContainer {
    private static final Logger LOG = Logger.getLogger(DockerJobContainer.class.getName());

    private final String _containerId;
    private final OutputStream _stdin;
    private final InputStream _stdout;
    private final InputStream _stderr;
    private final DockerClient _dockerClient;
    private final boolean _deleteContainer;

    public DockerJobContainer(DockerClient dockerClient, String containerId, DockerClient.ContainerStreams streams, boolean deleteContainer) {
        _dockerClient = checkNotNull(dockerClient);
        _containerId = checkNotNull(containerId);

        checkNotNull(streams);
        _stdin = streams.stdin;
        _stderr = streams.stderr;
        _stdout = streams.stdout;

        _deleteContainer = deleteContainer;
    }

    public String getContainerId() {
        return _containerId;
    }

    public OutputStream getStdin() {
        return _stdin;
    }

    public InputStream getStdout() {
        return _stdout;
    }

    public InputStream getStderr() {
        return _stderr;
    }

    public void stop() {
        closeStream(_stdin, "stdin");
        closeStream(_stdout, "stdout");
        closeStream(_stderr, "stderr");

        try {
            _dockerClient.stopContainer(_containerId);
        } catch (ContainerNotFoundException ex) {
            LOG.log(Level.FINE, format("Container not found: [containerId=%s]", _containerId), ex);
            return;
        } catch (DockerException ex) {
            LOG.log(Level.FINE, format("Error stopping container: [containerId=%s]", _containerId), ex);
        }

        if (_deleteContainer) {
            try {
                _dockerClient.removeContainer(_containerId);
            } catch (ContainerNotFoundException ex) {
                LOG.log(Level.FINE, format("Container not found: [containerId=%s]", _containerId), ex);
                return;
            } catch (DockerException ex) {
                LOG.log(Level.FINE, format("Error removing container: [containerId=%s]", _containerId), ex);
            }
        }
    }

    private void closeStream(Closeable stream, String name) {
        try {
            LOG.fine(format("Closing container %s: [containerId=%s]", name, _containerId));
            stream.close();
        } catch (Throwable ex) {
            LOG.log(Level.FINE, format("Error closing container %s: [containerId=%s]", name, _containerId), ex);
        }
    }
}
