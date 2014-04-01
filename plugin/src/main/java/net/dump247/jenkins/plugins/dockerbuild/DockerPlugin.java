package net.dump247.jenkins.plugins.dockerbuild;

import hudson.Plugin;
import hudson.model.LoadBalancer;
import jenkins.model.Jenkins;

public class DockerPlugin extends Plugin {
    @Override
    public void start() throws Exception {
        LoadBalancer currentLoadBalancer = Jenkins.getInstance().getQueue().getLoadBalancer();
        LoadBalancer dockerLoadBalancer = new DockerLoadBalancer(currentLoadBalancer);
        Jenkins.getInstance().getQueue().setLoadBalancer(dockerLoadBalancer);
    }
}
