package net.dump247.docker;

/** Request to kill a container. */
public class KillContainerRequest {
    private String _containerId;

    /**
     * ID of the container to kill or null if not yet provided.
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

    public KillContainerRequest withContainerId(final String containerId) {
        setContainerId(containerId);
        return this;
    }
}
