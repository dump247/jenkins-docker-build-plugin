package net.dump247.jenkins.plugins.dockerbuild;

import com.google.common.base.Optional;
import hudson.Plugin;
import hudson.model.LoadBalancer;
import jenkins.model.Jenkins;
import net.dump247.jenkins.plugins.dockerbuild.log.Logger;

public class DockerPlugin extends Plugin {
    private static final Logger LOG = Logger.get(DockerPlugin.class);

    public void start() throws Exception {
        // Swap out the default load balancer with the docker load balancer
        LoadBalancer currentLoadBalancer = Jenkins.getInstance().getQueue().getLoadBalancer();
        LoadBalancer dockerLoadBalancer = new DockerLoadBalancer(Optional.fromNullable(currentLoadBalancer));

        LOG.info("Injecting docker load balancer: fallback={0}", currentLoadBalancer);
        Jenkins.getInstance().getQueue().setLoadBalancer(dockerLoadBalancer);
    }
}
