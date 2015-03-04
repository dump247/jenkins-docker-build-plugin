package com.github.dump247.jenkins.plugins.dockerjob;

import hudson.Plugin;
import hudson.model.LoadBalancer;
import jenkins.model.Jenkins;

import java.util.logging.Logger;

import static com.github.dump247.jenkins.plugins.dockerjob.DockerJobLoadBalancer.NULL_LOAD_BALANCER;
import static com.google.common.base.Objects.firstNonNull;
import static java.util.logging.Level.FINE;

/**
 * Initializes the Jenkins build system for jobs in docker containers.
 */
public class DockerJobPlugin extends Plugin {
    private static final Logger LOG = Logger.getLogger(DockerJobPlugin.class.getName());

    public void start() throws Exception {
        LOG.info("Initializing Docker Job Plugin");
        Jenkins jenkins = Jenkins.getInstance();

        LoadBalancer existingLoadBalancer = firstNonNull(jenkins.getQueue().getLoadBalancer(), NULL_LOAD_BALANCER);
        DockerJobLoadBalancer dockerLoadBalancer = new DockerJobLoadBalancer(jenkins, existingLoadBalancer);

        LOG.log(FINE, "Injecting docker job plugin load balancer: fallback={0}", existingLoadBalancer);
        jenkins.getQueue().setLoadBalancer(dockerLoadBalancer);
    }
}
