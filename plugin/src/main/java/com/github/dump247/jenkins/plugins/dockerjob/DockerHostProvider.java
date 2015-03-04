package com.github.dump247.jenkins.plugins.dockerjob;

import com.google.common.net.HostAndPort;
import hudson.ExtensionPoint;
import hudson.model.AbstractDescribableImpl;

import java.util.Collection;

/**
 * Extension point for listing docker job host machines.
 */
public abstract class DockerHostProvider extends AbstractDescribableImpl<DockerHostProvider> implements ExtensionPoint {
    /**
     * List the hosts known to this provider.
     * <p/>
     * The port should be for connecting to the host via SSH. If not provided, the value
     * configured on the associated {@link DockerJobCloud} is used, which is 22 by default.
     */
    public abstract Collection<HostAndPort> listHosts() throws Exception;

    public static abstract class Descriptor extends hudson.model.Descriptor<DockerHostProvider> {
    }
}
