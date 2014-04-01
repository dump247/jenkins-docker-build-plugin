package net.dump247.jenkins.plugins.dockerbuild;

import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import hudson.model.LoadBalancer;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.queue.MappingWorksheet;
import hudson.model.queue.MappingWorksheet.ExecutorChunk;
import hudson.model.queue.MappingWorksheet.Mapping;
import hudson.model.queue.MappingWorksheet.WorkChunk;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.logging.Logger;

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
    private final Map<String, Node> _workChunkNodes = newHashMap();

    public DockerLoadBalancer(LoadBalancer fallbackLoadBalancer) {
        _fallbackLoadBalancer = fallbackLoadBalancer == null
                ? NULL_LOAD_BALANCER
                : fallbackLoadBalancer;
    }

    @Override
    public Mapping map(final Queue.Task task, final MappingWorksheet worksheet) {
        LOG.info(format("map(%s, %s)", task.getName(), worksheet));
        Mapping mapping = worksheet.new Mapping();

        for (int i = 0; i < worksheet.works.size(); i++) {
            WorkChunk workChunk = worksheet.works(i);
            String key = format("%s_%d", task.getFullDisplayName(), i);

            LOG.info(format("Mapping %s", key));

            // If executor is found, assign to work chunk
            Node workChunkNode = _workChunkNodes.get(key);

            try {
                // Node has not been provisioned or an old node exists in the map for the
                // given job key. This can happen if a previous job was canceled before it was
                // started.
                if (workChunkNode == null || workChunkNode.toComputer() == null) {
                    LOG.info(format("Provisioning: key=%s, label=%s", key, workChunk.assignedLabel));
                    DockerSlaveProvisioner provisioner = new DockerSlaveProvisioner(DockerGlobalConfiguration.get());
                    Optional<Node> provisionedNode = provisioner.provision(workChunk.assignedLabel);

                    if (provisionedNode.isPresent()) {
                        LOG.info("Node provisioning started!");
                        _workChunkNodes.put(key, provisionedNode.get());
                    } else {
                        LOG.warning("Unable to provision node");
                        return _fallbackLoadBalancer.map(task, worksheet);
                    }
                } else if (isReady(workChunkNode)) {
                    for (ExecutorChunk executorChunk : worksheet.works(i).applicableExecutorChunks()) {
                        if (executorChunk.node == workChunkNode) {
                            _workChunkNodes.remove(key);
                            LOG.info("Assigning work to executor");
                            mapping.assign(i, executorChunk);
                            break;
                        }
                    }

                    if (mapping.assigned(i) == null) {
                        LOG.warning("Failed to assign work to executor");
                    }
                } else {
                    LOG.info("Node still starting up. Will try again later");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException ex) {
                throw Throwables.propagate(ex);
            }
        }

        LOG.info(format("mapping: %s, valid: %s", mapping, mapping.isCompletelyValid()));
        return mapping.isCompletelyValid() ? mapping : null;
    }

    private static boolean isReady(Node workChunkNode) throws InterruptedException, ExecutionException {
        return workChunkNode.toComputer().isAcceptingTasks() &&
                workChunkNode.toComputer().isOnline() &&
                workChunkNode.toComputer().isIdle();
    }
}
