package net.dump247.docker;

/** Request to block until a container stops. */
public class WaitContainerRequest {
    private String _containerId;

    /**
     * ID of the container to wait for or null if not yet provided.
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

    public WaitContainerRequest withContainerId(final String containerId) {
        setContainerId(containerId);
        return this;
    }
}
