package net.dump247.jenkins.plugins.dockerbuild;

import hudson.model.Executor;
import hudson.model.Queue;
import hudson.remoting.Channel;
import hudson.slaves.SlaveComputer;
import net.dump247.jenkins.plugins.dockerbuild.log.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.Future;

import static java.lang.String.format;

/** Represents a computer running a jenkins job in a docker container. */
public class DockerComputer extends SlaveComputer {
    private static final Logger LOG = Logger.get(DockerComputer.class);

    private final DockerSlave _slave;

    private boolean _hasAcceptedJob = false;
    private boolean _hasCompletedJob = false;
    private long _nodeLaunchTimeMs = System.currentTimeMillis();

    public DockerComputer(final DockerSlave slave) {
        super(slave);
        _slave = slave;
    }

    public boolean hasCompletedJob() {
        return _hasCompletedJob;
    }

    public boolean hasAcceptedJob() {
        return _hasAcceptedJob;
    }

    public long getNodeLaunchTimeMs() {
        return _nodeLaunchTimeMs;
    }

    @Override
    public void taskAccepted(final Executor executor, final Queue.Task task) {
        super.taskAccepted(executor, task);
        LOG.debug("Docker task accepted: task={0}", task.getName());
        _hasAcceptedJob = true;
    }

    @Override
    public void taskCompleted(final Executor executor, final Queue.Task task, final long durationMS) {
        super.taskCompleted(executor, task, durationMS);
        LOG.debug("Docker task completed: task={0}", task.getName());
        jobComplete();
    }

    @Override
    public void taskCompletedWithProblems(final Executor executor, final Queue.Task task, final long durationMS, final Throwable problems) {
        super.taskCompletedWithProblems(executor, task, durationMS, problems);
        LOG.warn("Docker task completed with problems: task={0}", task.getName(), problems);
        jobComplete();
    }

    @Override
    public boolean isAcceptingTasks() {
        return !_hasCompletedJob && super.isAcceptingTasks();
    }

    @Override
    public void setChannel(final Channel channel, final OutputStream launchLog, final Channel.Listener listener) throws IOException, InterruptedException {
        super.setChannel(channel, launchLog, listener);
        _nodeLaunchTimeMs = System.currentTimeMillis();
    }

    public DockerSlave getSlave() {
        return _slave;
    }

    private void jobComplete() {
        _hasCompletedJob = true;

        try {
            _slave.terminate();
        } catch (IOException ex) {
            LOG.warn("Error terminating docker slave", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
