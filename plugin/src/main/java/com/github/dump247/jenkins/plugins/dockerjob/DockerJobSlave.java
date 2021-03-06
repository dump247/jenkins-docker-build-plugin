package com.github.dump247.jenkins.plugins.dockerjob;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Node;
import hudson.model.Slave;
import hudson.model.labels.LabelAtom;
import hudson.remoting.VirtualChannel;
import hudson.slaves.EphemeralNode;
import hudson.slaves.NodeProperty;
import jenkins.model.Jenkins;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.lang.String.format;

/**
 * Slave that
 */
public class DockerJobSlave extends Slave implements EphemeralNode {
    private static final Logger LOG = Logger.getLogger(DockerJobSlave.class.getName());
    private static final Joiner LABEL_JOINER = Joiner.on(' ');

    public boolean isMapped;
    public final String jobName;

    public DockerJobSlave(@Nonnull String nodeName, String nodeDescription, String jobName, String remoteFS, Set<LabelAtom> labels, DockerJobComputerLauncher launcher) throws Descriptor.FormException, IOException {
        super(nodeName,
                nodeDescription,
                remoteFS,
                1,
                Mode.NORMAL,
                LABEL_JOINER.join(labels),
                launcher,
                new DockerJobRetentionStrategy(),
                ImmutableList.<NodeProperty<?>>of());

        this.jobName = jobName;
    }

    @Override
    public Node asNode() {
        return this;
    }

    @Override
    public Computer createComputer() {
        return new DockerJobComputer(this);
    }

    @Override
    public DockerJobComputerLauncher getLauncher() {
        return (DockerJobComputerLauncher) super.getLauncher();
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
            } catch (IOException ex) {
                LOG.log(Level.WARNING, format("Failed to remove jenkins node: name=%s", this.name), ex);
            }
        }
    }
}
