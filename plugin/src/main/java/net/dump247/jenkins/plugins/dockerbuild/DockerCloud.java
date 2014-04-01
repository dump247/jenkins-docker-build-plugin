package net.dump247.jenkins.plugins.dockerbuild;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import hudson.Extension;
import hudson.model.Describable;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.labels.LabelAtom;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.google.common.base.Strings.nullToEmpty;
import static com.google.common.collect.Lists.newArrayList;
import static java.lang.String.format;
import static java.util.Collections.unmodifiableSet;

/** Set of machines to run jenkins slave docker containers on. */
public class DockerCloud implements Describable<DockerCloud> {
    private static final Logger LOG = Logger.getLogger(DockerCloud.class.getName());

    public final String hostString;
    private transient List<DockerCloudHost> _hosts;

    public final int dockerPort;

    public final String labelString;
    private transient Set<LabelAtom> _labels;

    public final int maxExecutors;

    @DataBoundConstructor
    public DockerCloud(final String hostString, final int dockerPort, final String labelString, final int maxExecutors) {
        this.hostString = hostString;
        this.dockerPort = dockerPort;
        this.labelString = labelString;
        this.maxExecutors = maxExecutors;

        readResolve();
    }

    /** Initialize transient fields after deserialization. */
    protected Object readResolve() {
        ImmutableList.Builder<DockerCloudHost> dockerHosts = ImmutableList.builder();

        for (String host : nullToEmpty(this.hostString).split("[,\\s]+")) {
            dockerHosts.add(new DockerCloudHost(URI.create("http://" + host + ":" + this.dockerPort)));
        }

        _hosts = dockerHosts.build();

        _labels = unmodifiableSet(Label.parse(this.labelString));

        return this;
    }

    @SuppressWarnings("unchecked")
    public hudson.model.Descriptor<DockerCloud> getDescriptor() {
        return Jenkins.getInstance().getDescriptor(getClass());
    }

    public List<DockerCloudHost> getHosts() {
        return _hosts;
    }

    public Set<LabelAtom> getLabels() {
        return _labels;
    }

    public Optional<Node> provision(final String imageName, final Set<LabelAtom> labels) {
        LOG.info(format("provision(%s, %s)", imageName, labels));

        List<HostCount> targetHosts = newArrayList();

        for (DockerCloudHost host : _hosts) {
            int count = host.countRunningNodes();

            if (count < this.maxExecutors) {
                targetHosts.add(new HostCount(host, count));
            }
        }

        // Sort from lowest to highest load so we attempt to provision on the least busy node first.
        Collections.sort(targetHosts);

        for (HostCount host : targetHosts) {
            try {
                LOG.info(format("Provisioning node: [host=%s] [load=%d]", host.host, host.count));
                return Optional.of(host.host.provision(imageName, labels));
            } catch (IOException ex) {
                LOG.log(Level.WARNING, format("Error provisioning node: [host=%s] [load=%d]", host.host, host.count), ex);
            }
        }

        return Optional.absent();
    }

    @Extension
    public static final class Descriptor extends hudson.model.Descriptor<DockerCloud> {
        @Override
        public String getDisplayName() {
            return "Docker Cloud";
        }
    }

    private static final class HostCount implements Comparable<HostCount> {
        public final int count;
        public final DockerCloudHost host;

        public HostCount(DockerCloudHost host, int count) {
            this.host = host;
            this.count = count;
        }

        public int compareTo(final HostCount hostCount) {
            // Sort from lowest to highest count
            return this.count - hostCount.count;
        }
    }
}
