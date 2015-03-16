package com.github.dump247.jenkins.plugins.dockerjob;

import hudson.model.AbstractProject;
import hudson.model.LoadBalancer;
import hudson.model.Queue;
import hudson.model.queue.MappingWorksheet;
import jenkins.model.Jenkins;

import java.util.logging.Logger;

import static com.github.dump247.jenkins.plugins.dockerjob.util.JenkinsUtils.getClouds;
import static com.github.dump247.jenkins.plugins.dockerjob.util.JenkinsUtils.getNodes;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.String.format;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.FINER;
import static java.util.logging.Level.WARNING;

/**
 * Creates docker containers on demand for Jenkins jobs.
 * <p/>
 * The {@link #map} method is polled at regular intervals by Jenkins until it maps the job to a
 * slave. If the job is managed by this plugin, this load balancer will launch a container for the
 * specific job and will only map the job to that specific container. If the job is not managed by
 * this container, it will fallback to the default load balancer implementation.
 * <p/>
 * This load balancer is injected into the Jenkins system in {@link DockerJobPlugin}.
 */
public class DockerJobLoadBalancer extends LoadBalancer {
    private static final Logger LOG = Logger.getLogger(DockerJobLoadBalancer.class.getName());

    /**
     * Load balancer that always returns null, which tells Jenkins there is no slave available.
     * This can be used as the fallback if no other load balancer is available.
     */
    public static final LoadBalancer NULL_LOAD_BALANCER = new LoadBalancer() {
        @Override
        public MappingWorksheet.Mapping map(Queue.Task task, MappingWorksheet worksheet) {
            return null;
        }
    };

    private final Jenkins _jenkins;
    private final LoadBalancer _fallback;

    public DockerJobLoadBalancer(Jenkins jenkins, LoadBalancer fallback) {
        _jenkins = checkNotNull(jenkins);
        _fallback = checkNotNull(fallback);
    }

    @Override
    public MappingWorksheet.Mapping map(Queue.Task task, MappingWorksheet worksheet) {
        LOG.log(FINER, "map({0}, {1}])", new Object[]{task.getFullDisplayName(), worksheet.works.size()});

        if (task instanceof AbstractProject) {
            return map((AbstractProject) task, worksheet);
        } else {
            return _fallback.map(task, worksheet);
        }
    }

    public MappingWorksheet.Mapping map(AbstractProject task, MappingWorksheet worksheet) {
        MappingWorksheet.Mapping mapping = worksheet.new Mapping();
        int mappedCount = 0;

        for (int workIndex = 0; workIndex < worksheet.works.size(); workIndex++) {
            MappingWorksheet.WorkChunk workChunk = worksheet.works(workIndex);

            String jobName = workChunk.index == 0
                    ? task.getFullDisplayName()
                    : format("%s_%d", task.getFullDisplayName(), workChunk.index);

            DockerJobSlave taskSlave = findSlave(jobName);

            if (taskSlave == null) {
                boolean supported = false;
                boolean mapped = false;

                for (DockerJobCloud cloud : getClouds(_jenkins, DockerJobCloud.class)) {
                    try {
                        DockerJobCloud.ProvisionResult result = cloud.provisionJob(jobName, task, workChunk);

                        if (result == DockerJobCloud.ProvisionResult.SUCCESS) {
                            LOG.log(FINE, "Successfully provisioned job: name={0} index={1} cloud={2}", new Object[]{task.getFullDisplayName(), workIndex, cloud.getDisplayName()});
                            mapped = true;
                            break;
                        } else if (result == DockerJobCloud.ProvisionResult.NO_CAPACITY) {
                            LOG.log(FINE, "Cloud capacity is exceeded: name={0} index={1} cloud={2}", new Object[]{task.getFullDisplayName(), workIndex, cloud.getDisplayName()});
                            supported = true;
                        }
                    } catch (Exception ex) {
                        LOG.log(WARNING, format("Failed to launch task: name=%s index=%d cloud=%s", task.getFullDisplayName(), workIndex, cloud.getDisplayName()), ex);
                    }
                }

                if (mapped) {
                    taskSlave = findSlave(jobName);
                } else if (supported) {
                    mappedCount += 1;
                }
            }

            LOG.log(FINER, "Slave: {0}", taskSlave);

            if (taskSlave != null) {
                mappedCount += 1;

                MappingWorksheet.ExecutorChunk executor = findExecutor(workChunk, taskSlave);

                if (executor != null) {
                    mapping.assign(workIndex, executor);
                }
            }
        }

        if (mappedCount == 0) {
            mapping = _fallback.map(task, worksheet);
        } else if (mappedCount < worksheet.works.size()) {
            LOG.log(WARNING, "Unable to launch job node because one or more tasks could not be mapped to a docker cloud. Mixed Docker and normal jobs are not supported. Job={0}", task.getFullDisplayName());
        }

        return mapping != null && mapping.isCompletelyValid()
                ? mapping
                : null;
    }

    private DockerJobSlave findSlave(String jobName) {
        for (DockerJobSlave slave : getNodes(_jenkins, DockerJobSlave.class)) {
            if (slave.getNodeName().equals(jobName)) {
                return slave;
            }
        }

        return null;
    }

    private MappingWorksheet.ExecutorChunk findExecutor(MappingWorksheet.WorkChunk task, DockerJobSlave slave) {
        for (MappingWorksheet.ExecutorChunk executor : task.applicableExecutorChunks()) {
            if (executor.node == slave) {
                return executor;
            }
        }

        return null;
    }
}
