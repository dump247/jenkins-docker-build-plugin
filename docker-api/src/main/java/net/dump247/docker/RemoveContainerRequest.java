package net.dump247.docker;

/** Request to remove a container from the filesystem. */
public class RemoveContainerRequest {
    private String _containerId;

    /**
     * ID of the container to remove or null if not yet provided.
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

    public RemoveContainerRequest withContainerId(final String containerId) {
        setContainerId(containerId);
        return this;
    }
}
