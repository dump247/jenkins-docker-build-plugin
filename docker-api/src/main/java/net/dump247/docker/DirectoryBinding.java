package net.dump247.docker;

/**
 * Bind a directory from the host machine to a specific path in the container.
 * <p/>
 * The container can be granted different {@link Access} permissions to the
 * bound directory.
 */
public class DirectoryBinding {
    private final String _hostPath;
    private final String _containerPath;
    private final Access _access;

    /**
     * Bind a host directory to a path in the container with
     * {@link Access#READ_WRITE} access.
     *
     * @param hostPath      path to the directory on the host machine
     * @param containerPath path to bind the host directory to in the container
     */
    public DirectoryBinding(final String hostPath, final String containerPath) {
        this(hostPath, containerPath, Access.READ_WRITE);
    }

    /**
     * Bind a host directory to a path in the container
     *
     * @param hostPath      path to the directory on the host machine
     * @param containerPath path to bind the host directory to in the container
     * @param access        access to the directory granted to the container
     */
    public DirectoryBinding(final String hostPath, final String containerPath, final Access access) {
        if (hostPath == null) {
            throw new NullPointerException("hostPath");
        }

        // TODO validate hostPath

        if (containerPath == null) {
            throw new NullPointerException("containerPath");
        }

        // TODO validate containerPath

        if (access == null) {
            throw new NullPointerException("access");
        }

        _hostPath = hostPath;
        _containerPath = containerPath;
        _access = access;
    }

    public String getHostPath() {
        return _hostPath;
    }

    public String getContainerPath() {
        return _containerPath;
    }

    public Access getAccess() {
        return _access;
    }

    public static DirectoryBinding readWrite(String path) {
        return new DirectoryBinding(path, path, Access.READ_WRITE);
    }

    public static DirectoryBinding readWrite(String hostPath, String containerPath) {
        return new DirectoryBinding(hostPath, containerPath, Access.READ_WRITE);
    }

    public static DirectoryBinding read(String path) {
        return new DirectoryBinding(path, path, Access.READ);
    }

    public static DirectoryBinding read(String hostPath, String containerPath) {
        return new DirectoryBinding(hostPath, containerPath, Access.READ);
    }

    public enum Access {
        READ,
        READ_WRITE;

        String toApiString() {
            switch (this) {
                case READ:
                    return "r";
                case READ_WRITE:
                    return "rw";
                default:
                    throw new UnsupportedOperationException(this.name());
            }
        }
    }
}
