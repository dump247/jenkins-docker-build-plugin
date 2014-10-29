package net.dump247.jenkins.plugins.dockerbuild;

import com.google.common.base.Optional;
import hudson.Plugin;
import hudson.model.LoadBalancer;
import jenkins.model.Jenkins;

import java.util.logging.Logger;

import static java.lang.String.format;

public class DockerPlugin extends Plugin {
    private static final Logger LOG = Logger.getLogger(DockerPlugin.class.getName());

    public void start() throws Exception {
        // Swap out the default load balancer with the docker load balancer
        LoadBalancer currentLoadBalancer = Jenkins.getInstance().getQueue().getLoadBalancer();
        LoadBalancer dockerLoadBalancer = new DockerLoadBalancer(Optional.fromNullable(currentLoadBalancer));

        LOG.info(format("Injecting docker load balancer: fallback=%s", currentLoadBalancer));
        Jenkins.getInstance().getQueue().setLoadBalancer(dockerLoadBalancer);
    }
}
