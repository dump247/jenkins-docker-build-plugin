package net.dump247.jenkins.plugins.dockerbuild;

import com.google.common.base.Optional;
import hudson.model.Computer;
import hudson.model.LoadBalancer;
import hudson.model.Queue;
import hudson.model.queue.MappingWorksheet;
import hudson.model.queue.MappingWorksheet.ExecutorChunk;
import hudson.model.queue.MappingWorksheet.Mapping;
import hudson.model.queue.MappingWorksheet.WorkChunk;
import hudson.slaves.Cloud;
import jenkins.model.Jenkins;
import net.dump247.jenkins.plugins.dockerbuild.log.Logger;

import java.util.Map;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Maps.newHashMap;

public class DockerLoadBalancer extends LoadBalancer {
    private static final String IMAGE_LABEL_PREFIX = "docker/";
    private static final Logger LOG = Logger.get(DockerLoadBalancer.class);
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
        LOG.debug("map({0}, {1} chunks)", task.getName(), worksheet.works.size());
        Mapping mapping = worksheet.new Mapping();
        boolean[] provisionedNodes = new boolean[worksheet.works.size()];
        int provisionedNodeCount = 0;
        DockerGlobalConfiguration configuration = DockerGlobalConfiguration.get();

        for (int workIndex = 0; workIndex < worksheet.works.size(); workIndex++) {
            WorkChunk workChunk = worksheet.works(workIndex);
            WorkSlave workSlave = loadSlave(configuration, workChunk);

            if (workSlave == null) {
                LOG.debug("No docker slave found for chunk {0}", workIndex);
                continue;
            }

            if (workSlave.isReady()) {
                ExecutorChunk workChunkExecutor = workSlave.findExecutor(workChunk);

                if (workChunkExecutor != null) {
                    LOG.debug("Mapped chunk {0} to executor {0}", workIndex, workChunkExecutor);
                    mapping.assign(workIndex, workChunkExecutor);
                }
            }

            provisionedNodes[workIndex] = true;
            provisionedNodeCount += 1;
        }

        // Use fallback load balancer for any work chunks that could not be provisioned with docker slaves
        if (provisionedNodeCount < worksheet.works.size()) {
            LOG.debug("Attempting to fall back for {0} chunks", worksheet.works.size());
            Mapping fallbackMapping = _fallbackLoadBalancer.map(task, worksheet);

            if (fallbackMapping != null) {
                for (int nodeIndex = 0; nodeIndex < worksheet.works.size(); nodeIndex += 1) {
                    if (!provisionedNodes[nodeIndex]) {
                        mapping.assign(nodeIndex, fallbackMapping.assigned(nodeIndex));
                    }
                }
            }
        }

        return mapping.isCompletelyValid()
                ? mapping
                : null;
    }

    private WorkSlave loadSlave(DockerGlobalConfiguration configuration, WorkChunk workChunk) {
        DockerSlave slave = _provisionedSlaves.get(workChunk);

        if (slave == null) {
            ProvisionResult result = provisionSlave(configuration, workChunk);

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

            ProvisionResult result = provisionSlave(configuration, workChunk);

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

    public ProvisionResult provisionSlave(DockerGlobalConfiguration configuration, WorkChunk workChunk) {
        boolean isSupported = false;

        for (Cloud cloud : Jenkins.getInstance().clouds) {
            if (cloud instanceof DockerCloud) {
                DockerCloud dockerCloud = (DockerCloud) cloud;
                ProvisionResult provisionResult = dockerCloud.provisionJob(workChunk);

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
