package net.dump247.jenkins.plugins.dockerbuild;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import hudson.model.Computer;
import hudson.model.Job;
import hudson.model.LoadBalancer;
import hudson.model.Queue;
import hudson.model.labels.LabelAtom;
import hudson.model.queue.MappingWorksheet;
import hudson.model.queue.MappingWorksheet.ExecutorChunk;
import hudson.model.queue.MappingWorksheet.Mapping;
import hudson.model.queue.MappingWorksheet.WorkChunk;

import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.collect.Maps.newHashMap;
import static java.lang.String.format;

public class DockerLoadBalancer extends LoadBalancer {
    private static final Logger LOG = Logger.getLogger(DockerLoadBalancer.class.getName());
    private static final LoadBalancer NULL_LOAD_BALANCER = new LoadBalancer() {
        @Override
        public Mapping map(final Queue.Task task, final MappingWorksheet worksheet) {
            return null;
        }
    };

    private final LoadBalancer _fallbackLoadBalancer;

    // TODO find a way to ensure removal of abandoned chunks (could happen if job is cancelled) to avoid memory leak
    private final Map<WorkChunk, DockerSlave> _provisionedSlaves = newHashMap();

    public DockerLoadBalancer(Optional<LoadBalancer> fallbackLoadBalancer) {
        _fallbackLoadBalancer = checkNotNull(fallbackLoadBalancer).or(NULL_LOAD_BALANCER);
    }

    @Override
    public Mapping map(final Queue.Task task, final MappingWorksheet worksheet) {
        LOG.info(format("map(%s, %s)", task.getName(), worksheet));
        Mapping mapping = worksheet.new Mapping();
        boolean[] provisionedNodes = new boolean[worksheet.works.size()];
        int provisionedNodeCount = 0;
        DockerGlobalConfiguration configuration = DockerGlobalConfiguration.get();

        for (int workIndex = 0; workIndex < worksheet.works.size(); workIndex++) {
            WorkChunk workChunk = worksheet.works(workIndex);
            WorkSlave workSlave = loadSlave(configuration, task, workChunk);

            if (workSlave == null) {
                LOG.info("No docker slave found");
                continue;
            }

            if (workSlave.isReady()) {
                ExecutorChunk workChunkExecutor = workSlave.findExecutor(workChunk);

                LOG.info(format("Executor: %s", workChunkExecutor));

                if (workChunkExecutor != null) {
                    mapping.assign(workIndex, workChunkExecutor);
                }
            }

            provisionedNodes[workIndex] = true;
            provisionedNodeCount += 1;
        }

        // Use fallback load balancer for any work chunks that could not be provisioned with docker slaves
        if (provisionedNodeCount < worksheet.works.size()) {
            Mapping fallbackMapping = _fallbackLoadBalancer.map(task, worksheet);

            if (fallbackMapping != null) {
                for (int nodeIndex = 0; nodeIndex < worksheet.works.size(); nodeIndex += 1) {
                    if (provisionedNodes[nodeIndex]) {
                        mapping.assign(nodeIndex, fallbackMapping.assigned(nodeIndex));
                    }
                }
            }
        }

        return mapping.isCompletelyValid()
                ? mapping
                : null;
    }

    private WorkSlave loadSlave(DockerGlobalConfiguration configuration, Queue.Task task, WorkChunk workChunk) {
        DockerSlave slave = _provisionedSlaves.get(workChunk);

        if (slave == null) {
            ProvisionResult result = provisionSlave(configuration, task, workChunk);

            if (!result.isSupported()) {
                return null;
            } else if (!result.isProvisioned()) {
                return WorkSlave.NO_CAPACITY;
            }

            slave = result.getSlave().get();
            _provisionedSlaves.put(workChunk, slave);
        }

        Computer slaveComputer = slave.toComputer();

        if (slaveComputer == null) {
            _provisionedSlaves.remove(workChunk);

            ProvisionResult result = provisionSlave(configuration, task, workChunk);

            if (!result.isSupported()) {
                return null;
            } else if (!result.isProvisioned()) {
                return WorkSlave.NO_CAPACITY;
            }

            slave = result.getSlave().get();
            slaveComputer = slave.toComputer();

            if (slaveComputer == null) {
                return null;
            }

            _provisionedSlaves.put(workChunk, slave);
        }

        return new WorkSlave(slave, slaveComputer);
    }

    public ProvisionResult provisionSlave(DockerGlobalConfiguration configuration, Queue.Task task, MappingWorksheet.WorkChunk workChunk) {
        boolean isSupported = false;

        if (task instanceof Job) {
            Job job = (Job) task;
            DockerJobProperty jobProperty = (DockerJobProperty) job.getProperty(DockerJobProperty.class);

            if (jobProperty != null && !isNullOrEmpty(jobProperty.getImageName())) {
                // User has explicitly enabled docker, so provisioning is always supported, even if
                // none of the docker hosts can run the job (i.e. labels don't match)
                isSupported = true;

                Set<LabelAtom> imageLabels = ImmutableSet.of(new LabelAtom("docker/" + jobProperty.getImageName()));
                ProvisionResult provisionResult = provisionSlaveInCloud(configuration.getClouds(), jobProperty.getImageName(), workChunk, imageLabels);

                if (provisionResult.isProvisioned()) {
                    return provisionResult;
                }
            }
        }

        if (!isSupported) {
            for (LabeledDockerImage image : configuration.getLabeledImages()) {
                Set<LabelAtom> imageLabels = Sets.union(image.getLabels(), ImmutableSet.of(new LabelAtom("docker/" + image.imageName)));
                ProvisionResult provisionResult = provisionSlaveInCloud(configuration.getClouds(), image.imageName, workChunk, imageLabels);

                if (provisionResult.isProvisioned()) {
                    return provisionResult;
                } else if (provisionResult.isSupported()) {
                    isSupported = true;
                }
            }
        }

        return isSupported
                ? ProvisionResult.noCapacity()
                : ProvisionResult.notSupported();
    }

    private ProvisionResult provisionSlaveInCloud(Iterable<DockerCloud> clouds, String imageName, WorkChunk workChunk, Set<LabelAtom> imageLabels) {
        boolean isSupported = false;

        for (DockerCloud cloud : clouds) {
            ProvisionResult provisionResult = cloud.provisionJob(Optional.fromNullable(workChunk.assignedLabel), imageName, imageLabels);

            if (provisionResult.isProvisioned()) {
                return provisionResult;
            } else if (provisionResult.isSupported()) {
                isSupported = true;
            }
        }

        return isSupported
                ? ProvisionResult.noCapacity()
                : ProvisionResult.notSupported();
    }

    private static final class WorkSlave {
        private static final WorkSlave NO_CAPACITY = new WorkSlave(null, null);

        private final DockerSlave slave;
        private final Computer computer;

        public WorkSlave(DockerSlave slave, Computer computer) {
            this.slave = slave;
            this.computer = computer;
        }

        public boolean isReady() {
            return computer != null &&
                    computer.isAcceptingTasks() &&
                    computer.isOnline() &&
                    computer.isIdle();
        }

        public ExecutorChunk findExecutor(WorkChunk workChunk) {
            for (ExecutorChunk executor : workChunk.applicableExecutorChunks()) {
                if (executor.node == slave) {
                    return executor;
                }
            }

            return null;
        }
    }
}
