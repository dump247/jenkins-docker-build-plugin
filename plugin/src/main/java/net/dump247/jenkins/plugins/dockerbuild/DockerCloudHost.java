package net.dump247.jenkins.plugins.dockerbuild;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import hudson.model.Node;
import jenkins.model.Jenkins;
import net.dump247.docker.DockerClient;
import net.dump247.docker.DockerException;
import net.dump247.docker.DockerVersionResponse;

import java.util.List;
import java.util.Map;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Machine that starts jenkins slaves inside of docker containers.
 */
public class DockerCloudHost {
    private final DockerClient _client;

    public DockerCloudHost(DockerClient client) {
        _client = checkNotNull(client);
    }

    public DockerClient getClient() {
        return _client;
    }

    @Override
    public String toString() {
        return _client.getEndpoint().toString();
    }

    public Map<String, Object> status() {
        try {
            DockerVersionResponse version = _client.version();

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

                if (launcher.getDockerJob().getDockerClient().getEndpoint().equals(_client.getEndpoint())) {
                    count += 1;
                }
            }
        }

        return count;
    }
}
