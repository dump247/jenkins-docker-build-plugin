package net.dump247.jenkins.plugins.dockerbuild;

import hudson.slaves.RetentionStrategy;
import net.dump247.jenkins.plugins.dockerbuild.log.Logger;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static java.lang.String.format;

/**
 * Consulted by jenkins whether to terminate the computer running a job.
 * <p/>
 * This retention strategy allows jenkins to terminate after a single job has
 * been run.
 */
public class DockerRetentionStrategy extends RetentionStrategy<DockerComputer> {
    private static final Logger LOG = Logger.get(DockerRetentionStrategy.class);
    private static final long JOB_ACCEPT_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(1);

    @DataBoundConstructor
    public DockerRetentionStrategy() {
    }

    @Override
    public long check(final DockerComputer computer) {
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
            LOG.debug("Terminating docker slave");
            computer.getSlave().terminate();
        } catch (IOException ex) {
            LOG.warn("Error terminating docker slave", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
