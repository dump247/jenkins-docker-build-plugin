package net.dump247.docker;

/**
 * Volume associated with a container.
 * <p/>
 * Volumes are separate filesystems that can be mounted to a specific location
 * in the container. A volume can then be mapped to a directory on the host
 * file system or mapped into a different container.
 */
public class ContainerVolume {
    private final String _path;

    public ContainerVolume(final String path) {
        if (path == null) {
            throw new NullPointerException("path");
        }

        // TODO validate path

        _path = path;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof ContainerVolume &&
                (this == obj || _path.equals(((ContainerVolume) obj)._path));

    }

    @Override
    public int hashCode() {
        return _path.hashCode();
    }

    public String getPath() {
        return _path;
    }
}
