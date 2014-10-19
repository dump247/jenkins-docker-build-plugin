package net.dump247.jenkins.plugins.dockerbuild;

import com.google.common.collect.ImmutableList;
import hudson.Extension;
import net.dump247.jenkins.plugins.dockerbuild.log.Logger;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.Collection;
import java.util.List;

import static com.google.common.base.Strings.nullToEmpty;

/**
 * Set of machines to run jenkins slave docker containers on.
 */
public class StaticDockerCloud extends DockerCloud {
    public final String hostString;
    private transient List<DockerCloudHost> _hosts;

    @DataBoundConstructor
    public StaticDockerCloud(final String hostString, final int dockerPort, final String labelString, final int maxExecutors, final boolean tlsEnabled, final String credentialsId, final String directoryMappingsString) {
        super(dockerPort, labelString, maxExecutors, tlsEnabled, credentialsId, directoryMappingsString);

        this.hostString = hostString;

        readResolve();
    }

    @Override
    public Collection<DockerCloudHost> listHosts() {
        return _hosts;
    }

    @Override
    protected Object readResolve() {
        ImmutableList.Builder<DockerCloudHost> dockerHosts = ImmutableList.builder();

        for (String host : nullToEmpty(this.hostString).split("[,\\s]+")) {
            dockerHosts.add(new DockerCloudHost(buildDockerClient(host)));
        }

        _hosts = dockerHosts.build();

        return super.readResolve();
    }

    @Extension
    public static final class Descriptor extends DockerCloud.Descriptor {
        @Override
        public String getDisplayName() {
            return "Static Hosts";
        }
    }
}
