package net.dump247.jenkins.plugins.dockerbuild;

import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.model.Slave;
import hudson.model.labels.LabelAtom;
import hudson.remoting.VirtualChannel;
import hudson.slaves.EphemeralNode;
import hudson.slaves.NodeProperty;
import jenkins.model.Jenkins;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.lang.String.format;

/**
 * Slave that runs a job in a docker image.
 */
public class DockerSlave extends Slave implements EphemeralNode {
    private static final Logger LOG = Logger.getLogger(DockerSlave.class.getName());

    public DockerSlave(final String name,
                       final String nodeDescription,
                       final String remoteFS,
                       final Set<LabelAtom> labels,
                       final DockerComputerLauncher launcher)
            throws Descriptor.FormException, IOException {
        super(name,
                nodeDescription,
                remoteFS,
                1,
                Mode.EXCLUSIVE,
                Joiner.on(' ').join(labels),
                launcher,
                new DockerRetentionStrategy(),
                new ArrayList<NodeProperty<?>>());
    }

    @Override
    public Computer createComputer() {
        return new DockerComputer(this);
    }

    public void terminate() throws IOException, InterruptedException {
        try {
            VirtualChannel channel = getChannel();

            if (channel != null) {
                channel.close();
            }
        } finally {
            try {
                Jenkins.getInstance().removeNode(this);
            } catch (IOException e) {
                LOG.log(Level.WARNING, format("Failed to remove jenkins node: name=%s", this.name), e);
            }
        }
    }

    public Node asNode() {
        return this;
    }

    @Extension
    public static final class Descriptor extends SlaveDescriptor {
        @Override
        public String getDisplayName() {
            return "Docker Slave";
        }

        @Override
        public boolean isInstantiable() {
            return false;
        }
    }
}
