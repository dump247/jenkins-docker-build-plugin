package net.dump247.jenkins.plugins.dockerbuild;

import com.google.common.base.Optional;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Result of attempting to provision a Jenkins task in a Docker container.
 * <p/>
 * There are 3 possible results:
 * <ul>
 *     <li>the slave was successfully provisioned</li>
 *     <li>the task <i>could</i> be provisioned on a docker slave, but there is no available capacity</li>
 *     <li>docker provisioning is not supported for the task</li>
 * </ul>
 */
public class ProvisionResult {
    private static final ProvisionResult NOT_SUPPORTED = new ProvisionResult(false, Optional.<DockerSlave>absent());
    private static final ProvisionResult NO_CAPACITY = new ProvisionResult(true, Optional.<DockerSlave>absent());

    private boolean _isSupported;
    private Optional<DockerSlave> _slave;

    private ProvisionResult(boolean isSupported, Optional<DockerSlave> slave) {
        _isSupported = isSupported;
        _slave = slave;
    }

    public boolean isSupported() {
        return _isSupported;
    }

    public boolean isProvisioned() {
        return _slave.isPresent();
    }

    public Optional<DockerSlave> getSlave() {
        return _slave;
    }

    public static ProvisionResult notSupported() {
        return NOT_SUPPORTED;
    }

    public static ProvisionResult noCapacity() {
        return NO_CAPACITY;
    }

    public static ProvisionResult provisioned(DockerSlave slave) {
        return new ProvisionResult(true, Optional.of(slave));
    }

    public static ProvisionResult supported(Optional<DockerSlave> slave) {
        return new ProvisionResult(true, checkNotNull(slave));
    }
}
