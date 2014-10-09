package net.dump247.jenkins.plugins.dockerbuild;

import com.google.common.base.Optional;
import hudson.Plugin;
import hudson.model.LoadBalancer;
import jenkins.model.Jenkins;

public class DockerPlugin extends Plugin {
    @Override
    public void start() throws Exception {
        // Swap out the default load balancer with the docker load balancer
        LoadBalancer currentLoadBalancer = Jenkins.getInstance().getQueue().getLoadBalancer();
        LoadBalancer dockerLoadBalancer = new DockerLoadBalancer(Optional.fromNullable(currentLoadBalancer));
        Jenkins.getInstance().getQueue().setLoadBalancer(dockerLoadBalancer);
    }
}
