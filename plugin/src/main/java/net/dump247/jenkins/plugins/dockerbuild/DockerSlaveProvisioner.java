package net.dump247.jenkins.plugins.dockerbuild;

import com.google.common.base.Optional;
import com.google.common.collect.Sets;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.labels.LabelAtom;

import java.util.Collections;
import java.util.Set;
import java.util.logging.Logger;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.String.format;

/** Provision slaves to run jobs in docker containers. */
public class DockerSlaveProvisioner {
    private static final Logger LOG = Logger.getLogger(DockerSlaveProvisioner.class.getName());

    private final DockerGlobalConfiguration _configuration;

    public DockerSlaveProvisioner(final DockerGlobalConfiguration configuration) {
        _configuration = checkNotNull(configuration);
    }

    public Optional<Node> provision(final Label label) {
        return provision(label, null);
    }

    public Optional<Node> provision(final Label label, final String imageName) {
        LOG.info(format("provision(%s, %s)", label, imageName));

        // No hosts to provision the job on
        if (_configuration.getClouds().size() == 0) {
            return Optional.absent();
        }

        if (imageName != null) {
            return provisionNode(imageName, Collections.<LabelAtom>emptySet(), label);
        } else {
            for (DockerLabeledImage image : _configuration.getLabeledImages()) {
                Optional<Node> provisionedNode = provisionNode(image.imageName, image.getLabels(), label);

                if (provisionedNode.isPresent()) {
                    return provisionedNode;
                }
            }
        }

        return Optional.absent();
    }

    private Optional<Node> provisionNode(final String imageName, Set<LabelAtom> imageLabels, Label label) {
        for (DockerCloud cloud : _configuration.getClouds()) {
            Set<LabelAtom> labels = Sets.union(imageLabels, cloud.getLabels());

            if (label == null || label.matches(labels)) {
                Optional<Node> provisionedNode = cloud.provision(imageName, labels);

                if (provisionedNode.isPresent()) {
                    return provisionedNode;
                }
            }
        }

        return Optional.absent();
    }
}
