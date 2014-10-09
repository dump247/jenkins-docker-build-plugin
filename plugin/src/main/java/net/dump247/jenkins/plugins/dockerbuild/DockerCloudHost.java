package net.dump247.jenkins.plugins.dockerbuild;

import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Node;
import hudson.model.labels.LabelAtom;
import jenkins.model.Jenkins;
import net.dump247.docker.DirectoryBinding;
import net.dump247.docker.DockerClient;
import org.apache.commons.lang.RandomStringUtils;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.String.format;

/**
 * Machine that starts jenkins slaves inside of docker containers.
 */
public class DockerCloudHost {
    private static final Logger LOG = Logger.getLogger(DockerCloudHost.class.getName());

    private final DockerClient _dockerClient;

    public DockerCloudHost(URI dockerEndpoint) {
        _dockerClient = new DockerClient(dockerEndpoint);
    }

    public DockerCloudHost(DockerClient client) {
        _dockerClient = checkNotNull(client);
    }

    @Override
    public String toString() {
        return _dockerClient.getEndpoint().toString();
    }

    public int countRunningJobs() {
        List<Node> nodes = Jenkins.getInstance().getNodes();
        int count = 0;

        for (Node node : nodes) {
            if (node instanceof DockerSlave) {
                DockerSlave dockerSlave = (DockerSlave) node;
                DockerComputerLauncher launcher = (DockerComputerLauncher) dockerSlave.getLauncher();

                if (launcher.getDockerClient().getEndpoint().equals(_dockerClient.getEndpoint())) {
                    count += 1;
                }
            }
        }

        LOG.info(format("count: %d, endpoint: %s", count, _dockerClient.getEndpoint()));

        return count;
    }

    public DockerSlave provisionSlave(String imageName, Set<LabelAtom> labels, List<DirectoryBinding> directoryBindings) throws IOException {
        checkNotNull(imageName);
        checkNotNull(labels);
        checkNotNull(directoryBindings);

        try {
            DockerSlave slave = new DockerSlave(
                    format("%s (%s)", imageName, RandomStringUtils.randomAlphanumeric(6).toLowerCase()),
                    "Running job on image " + imageName,
                    DockerComputerLauncher.JENKINS_CONTAINER_HOME,
                    labels,
                    new DockerComputerLauncher(_dockerClient, imageName, directoryBindings)
            );

            Jenkins.getInstance().addNode(slave);

            LOG.info("Starting slave computer...");
            Computer slaveComputer = slave.toComputer();
            slaveComputer.connect(false);
            LOG.info("Slave computer started: " + slaveComputer.getClass());

            return slave;
        } catch (Descriptor.FormException ex) {
            throw new IllegalArgumentException(ex);
        }
    }
}
