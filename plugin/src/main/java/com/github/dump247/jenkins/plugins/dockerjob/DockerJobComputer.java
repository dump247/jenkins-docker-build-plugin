package com.github.dump247.jenkins.plugins.dockerjob;

import hudson.model.Executor;
import hudson.model.Queue;
import hudson.remoting.Channel;
import hudson.slaves.SlaveComputer;
import org.joda.time.Duration;
import org.joda.time.Instant;

import java.io.IOException;
import java.io.OutputStream;
import java.util.logging.Logger;

import static java.lang.String.format;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.WARNING;

public class DockerJobComputer extends SlaveComputer {
    private static final Logger LOG = Logger.getLogger(DockerJobComputer.class.getName());

    private final DockerJobSlave _slave;

    private boolean _hasAcceptedJob = false;
    private boolean _hasCompletedJob = false;
    private Instant _nodeLaunchTimeMs = Instant.now();

    public DockerJobComputer(DockerJobSlave slave) {
        super(slave);
        _slave = slave;
    }

    public boolean hasCompletedJob() {
        return _hasCompletedJob;
    }

    public boolean hasAcceptedJob() {
        return _hasAcceptedJob;
    }

    @Override
    public void taskAccepted(final Executor executor, final Queue.Task task) {
        super.taskAccepted(executor, task);
        LOG.fine(format("Docker task accepted: task=%s", task.getName()));
        _hasAcceptedJob = true;
    }

    @Override
    public void taskCompleted(final Executor executor, final Queue.Task task, final long durationMS) {
        super.taskCompleted(executor, task, durationMS);
        LOG.fine(format("Docker task completed: task=%s", task.getName()));
        terminate();
    }

    @Override
    public void taskCompletedWithProblems(final Executor executor, final Queue.Task task, final long durationMS, final Throwable problems) {
        super.taskCompletedWithProblems(executor, task, durationMS, problems);
        LOG.log(WARNING, format("Docker task completed with problems: task=%s", task.getName()), problems);
        terminate();
    }

    @Override
    public boolean isAcceptingTasks() {
        return !_hasCompletedJob && super.isAcceptingTasks();
    }

    @Override
    public void setChannel(final Channel channel, final OutputStream launchLog, final Channel.Listener listener) throws IOException, InterruptedException {
        super.setChannel(channel, launchLog, listener);
        _nodeLaunchTimeMs = Instant.now();
    }

    public boolean hasCompletedJob(Duration launchTimeout) {
        return hasCompletedJob() ||
                (!hasAcceptedJob() && _nodeLaunchTimeMs.plus(launchTimeout).isBefore(Instant.now())) ||
                (hasAcceptedJob() && isOffline());
    }

    public void terminate() {
        LOG.log(FINE, "Terminating job: name={0}", _slave.getNodeName());
        _hasCompletedJob = true;

        try {
            _slave.terminate();
        } catch (IOException ex) {
            LOG.log(WARNING, "Error terminating docker slave", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
