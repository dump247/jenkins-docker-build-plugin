package com.github.dump247.jenkins.plugins.dockerjob;

import hudson.slaves.RetentionStrategy;
import org.joda.time.Duration;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Terminates slaves that fail to launch.
 * <p/>
 * Jenkins regularly polls the retention strategy to update the state of a computer. This strategy
 * does some checking to ensure that the slave launches in a timely manner and terminates the
 * slave once a single job has completed.
 */
public class DockerJobRetentionStrategy extends RetentionStrategy<DockerJobComputer> {
    private static final Duration JOB_ACCEPT_TIMEOUT = Duration.standardSeconds(30);

    @DataBoundConstructor
    public DockerJobRetentionStrategy() {
    }

    @Override
    public long check(DockerJobComputer computer) {
        if (computer.hasCompletedJob(JOB_ACCEPT_TIMEOUT)) {
            computer.terminate();
        }

        return 1;
    }
}
