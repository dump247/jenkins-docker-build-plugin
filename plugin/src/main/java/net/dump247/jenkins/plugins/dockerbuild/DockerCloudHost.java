package net.dump247.jenkins.plugins.dockerbuild;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Node;
import hudson.model.labels.LabelAtom;
import jenkins.model.Jenkins;
import net.dump247.docker.DirectoryBinding;
import net.dump247.docker.DockerClient;
import net.dump247.docker.DockerException;
import net.dump247.docker.DockerVersionResponse;
import org.apache.commons.lang.RandomStringUtils;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.String.format;

/**
 * Machine that starts jenkins slaves inside of docker containers.
 */
public class DockerCloudHost {
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

    public Map<String, Object> status() {
        try {
            DockerVersionResponse version = _dockerClient.version();

            return ImmutableMap.<String, Object>builder()
                    .put("docker", ImmutableMap.of(
                            "version", version.getVersion(),
                            "commit", version.getGitCommit(),
                            "api", version.getApiVersion(),
                            "go", version.getGoVersion()
                    ))
                    .put("os", ImmutableMap.of(
                            "arch", version.getArch(),
                            "name", version.getOs(),
                            "kernel", version.getKernelVersion()
                    ))
                    .build();
        } catch (DockerException ex) {
            throw Throwables.propagate(ex);
        }
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
                    DockerComputerLauncher.JENKINS_SHARED_DIR,
                    labels,
                    new DockerComputerLauncher(_dockerClient, imageName, directoryBindings)
            );

            Jenkins.getInstance().addNode(slave);

            Computer slaveComputer = slave.toComputer();
            slaveComputer.connect(false);

            return slave;
        } catch (Descriptor.FormException ex) {
            throw new IllegalArgumentException(ex);
        }
    }
}
