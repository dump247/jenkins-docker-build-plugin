package net.dump247.jenkins.plugins.dockerbuild;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import hudson.model.Computer;
import hudson.model.Label;
import hudson.model.LoadBalancer;
import hudson.model.Queue;
import hudson.model.labels.LabelAtom;
import hudson.model.queue.MappingWorksheet;
import hudson.model.queue.MappingWorksheet.ExecutorChunk;
import hudson.model.queue.MappingWorksheet.Mapping;
import hudson.model.queue.MappingWorksheet.WorkChunk;
import net.dump247.jenkins.plugins.dockerbuild.log.Logger;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Maps.newHashMap;

public class DockerLoadBalancer extends LoadBalancer {
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

        for (LabelAtom potentialImage : listPotentialImages(workChunk.assignedLabel)) {
            Set<LabelAtom> imageLabels = ImmutableSet.of(potentialImage);
            ProvisionResult provisionResult = provisionSlaveInCloud(configuration.getClouds(), extractImageName(potentialImage), workChunk, imageLabels);

            if (provisionResult.isProvisioned()) {
                return provisionResult;
            } else if (provisionResult.isSupported()) {
                isSupported = true;
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

    private String extractImageName(LabelAtom imageLabel) {
        return imageLabel.toString().substring("docker/".length());
    }

    public List<LabelAtom> listPotentialImages(Label jobLabel) {
        return discoverPotentialImages(jobLabel, ImmutableList.<LabelAtom>builder()).build();
    }

    public ImmutableList.Builder<LabelAtom> discoverPotentialImages(Label jobLabel, ImmutableList.Builder<LabelAtom> results) {
        if (jobLabel == null) {
            return results;
        }

        if (jobLabel instanceof LabelAtom) {
            LabelAtom imageLabel = (LabelAtom) jobLabel;
            String labelStr = imageLabel.toString();

            if (labelStr.startsWith("docker/") && labelStr.length() > "docker/".length()) {
                results.add((LabelAtom) jobLabel);
            }

            return results;
        }

        for (Field field : jobLabel.getClass().getFields()) {
            if (Label.class.isAssignableFrom(field.getType())) {
                try {
                    discoverPotentialImages((Label) field.get(jobLabel), results);
                } catch (IllegalAccessException e) {
                    LOG.warn("Error attempting to get value of label field: name={0} type={1}", field.getName(), jobLabel.getClass());
                }
            }
        }

        return results;
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
