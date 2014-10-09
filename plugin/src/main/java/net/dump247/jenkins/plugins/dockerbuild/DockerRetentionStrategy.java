package net.dump247.jenkins.plugins.dockerbuild;

import hudson.slaves.RetentionStrategy;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.lang.String.format;

/**
 * Consulted by jenkins whether to terminate the computer running a job.
 * <p/>
 * This retention strategy allows jenkins to terminate after a single job has
 * been run.
 */
public class DockerRetentionStrategy extends RetentionStrategy<DockerComputer> {
    private static final Logger LOG = Logger.getLogger(DockerRetentionStrategy.class.getName());
    private static final long JOB_ACCEPT_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(1);

    @DataBoundConstructor
    public DockerRetentionStrategy() {
    }

    @Override
    public long check(final DockerComputer computer) {
        LOG.info(format("check: isIdle=%s isOnline=%s hasRunJob=%s hasAcceptedJob=%s launchTimeMs=%d isAcceptingTasks=%s isOffline=%s isConnecting=%s",
                computer.isIdle(),
                computer.isOnline(),
                computer.hasCompletedJob(),
                computer.hasAcceptedJob(),
                computer.getNodeLaunchTimeMs(),
                computer.isAcceptingTasks(),
                computer.isOffline(),
                computer.isConnecting()));

        if (computer.isOnline()) {
            if (computer.hasCompletedJob() || (!computer.hasAcceptedJob() && hasTimedOut(computer, JOB_ACCEPT_TIMEOUT_MS))) {
                terminate(computer);
            }
        }

        return 1;
    }

    private boolean hasTimedOut(final DockerComputer computer, long timeout) {
        return (System.currentTimeMillis() - computer.getNodeLaunchTimeMs()) > timeout;
    }

    private void terminate(final DockerComputer computer) {
        try {
            LOG.info("Terminating docker slave");
            computer.getSlave().terminate();
        } catch (IOException ex) {
            LOG.log(Level.WARNING, "Error terminating docker container.", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
