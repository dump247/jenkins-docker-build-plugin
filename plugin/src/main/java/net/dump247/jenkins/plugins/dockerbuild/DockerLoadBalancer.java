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

import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.google.common.base.Preconditions.checkNotNull;
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
        LOG.finer(format("map(%s, %d chunks)", task.getName(), worksheet.works.size()));
        Mapping mapping = worksheet.new Mapping();
        int provisionedNodeCount = 0;
        DockerGlobalConfiguration configuration = DockerGlobalConfiguration.get();

        for (int workIndex = 0; workIndex < worksheet.works.size(); workIndex++) {
            WorkChunk workChunk = worksheet.works(workIndex);
            WorkSlave workSlave = loadSlave(configuration, task, workChunk);

            if (workSlave == null) {
                continue;
            } else if (workSlave == WorkSlave.NO_CAPACITY) {
                LOG.warning(format("%s[%d]: No docker cloud capacity available to provision job", task.getFullDisplayName(), workIndex));
            } else {
                ExecutorChunk workChunkExecutor = workSlave.findExecutor(workChunk);

                if (workChunkExecutor != null) {
                    LOG.fine(format("%s[%d]: Task assigned to docker node %s", task.getFullDisplayName(), workIndex, workChunkExecutor));
                    mapping.assign(workIndex, workChunkExecutor);
                } else {
                    LOG.fine(format("%s[%d]: Docker node provisioning in process", task.getFullDisplayName(), workIndex));
                }
            }

            provisionedNodeCount += 1;
        }

        if (!mapping.isCompletelyValid() && provisionedNodeCount < worksheet.works.size()) {
            if (provisionedNodeCount != 0) {
                LOG.log(Level.SEVERE, "" +
                                "%s: Partial docker and default provisioning is not supported. " +
                                "All tasks must be docker enabled or not docker enabled. " +
                                "This configuration is not currently supported, but may be in the future.",
                        task.getFullDisplayName());
            } else {
                LOG.fine(format("%s: Using default task node mapper", task.getFullDisplayName()));
                mapping = _fallbackLoadBalancer.map(task, worksheet);
            }
        }

        return mapping != null && mapping.isCompletelyValid()
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

    public ProvisionResult provisionSlave(DockerGlobalConfiguration configuration, Queue.Task task, WorkChunk workChunk) {
        boolean isSupported = false;

        for (Cloud cloud : Jenkins.getInstance().clouds) {
            if (cloud instanceof DockerCloud) {
                DockerCloud dockerCloud = (DockerCloud) cloud;
                ProvisionResult provisionResult = dockerCloud.provisionJob(configuration, task, workChunk);

                if (provisionResult.isProvisioned()) {
                    LOG.fine(format("Provisioned docker job node: job=%s[%d] cloud=%s", task.getFullDisplayName(), workChunk.index, dockerCloud.getDisplayName()));
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
