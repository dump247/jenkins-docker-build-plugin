package net.dump247.jenkins.plugins.dockerbuild;

import com.google.common.collect.Sets;
import hudson.model.Descriptor;
import hudson.model.Node;
import hudson.model.labels.LabelAtom;
import jenkins.model.Jenkins;
import net.dump247.docker.DockerClient;
import org.apache.commons.lang.RandomStringUtils;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import static com.google.inject.internal.guava.collect.$Sets.newHashSet;
import static java.lang.String.format;

/** Machine that starts jenkins slaves inside of docker containers. */
public class DockerCloudHost {
    private static final Logger LOG = Logger.getLogger(DockerCloudHost.class.getName());

    private final DockerClient _dockerClient;
    private final String _jenkinsSlavePath;

    public DockerCloudHost(URI dockerEndpoint, final String jenkinsSlavePath) {
        _dockerClient = new DockerClient(dockerEndpoint);
        _jenkinsSlavePath = jenkinsSlavePath;
    }

    @Override
    public String toString() {
        return _dockerClient.getEndpoint().toString();
    }

    public int countRunningNodes() {
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

    public Node provision(final String imageName, final Set<LabelAtom> labels) throws IOException {
        try {
            DockerSlave slave = new DockerSlave(
                    format("%s (%s)", imageName, RandomStringUtils.randomAlphanumeric(6).toLowerCase()),
                    "Running job on image " + imageName,
                    "/home/jenkins",
                    Sets.union(labels, newHashSet(
                            new LabelAtom("docker/" + imageName))),
                    new DockerComputerLauncher(_dockerClient, imageName, _jenkinsSlavePath)
            );

            Jenkins.getInstance().addNode(slave);

            LOG.info("Starting slave computer...");
            slave.toComputer().connect(false);

            return slave;
        } catch (Descriptor.FormException ex) {
            throw new IllegalArgumentException(ex);
        }
    }
}
