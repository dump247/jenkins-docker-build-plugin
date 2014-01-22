package net.dump247.docker;

/** Request to stop a container. */
public class StopContainerRequest {
    private String _containerId;
    private int _timeoutSeconds;

    /**
     * ID of the container to stop or null if not yet provided.
     *
     * @return container id
     */
    public String getContainerId() {
        return _containerId;
    }

    public void setContainerId(final String containerId) {
        if (containerId == null) {
            throw new NullPointerException("containerId");
        }

        // TODO validate containerId

        _containerId = containerId;
    }

    public StopContainerRequest withContainerId(final String containerId) {
        setContainerId(containerId);
        return this;
    }

    /**
     * Number of seconds to wait before killing the container or 0 to not kill.
     *
     * @return kill timeout
     */
    public int getTimeoutSeconds() {
        return _timeoutSeconds;
    }

    public void setTimeoutSeconds(final int timeoutSeconds) {
        if (timeoutSeconds < 0) {
            throw new IllegalArgumentException("timeoutSeconds must be greater than or equal to 0");
        }

        _timeoutSeconds = timeoutSeconds;
    }

    public StopContainerRequest withTimeoutSeconds(final int timeoutSeconds) {
        setTimeoutSeconds(timeoutSeconds);
        return this;
    }
}
