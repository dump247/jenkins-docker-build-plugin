package net.dump247.jenkins.plugins.dockerbuild;

import hudson.Extension;
import hudson.model.Describable;
import hudson.model.Label;
import hudson.model.labels.LabelAtom;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.Set;

/**
 * Labels applied to a specific docker image.
 * <p/>
 * This is used to configure an image that can be selected using label expressions
 * in the job configuration.
 */
public class DockerLabeledImage implements Describable<DockerLabeledImage> {
    public final String labelString;
    private transient Set<LabelAtom> _labels;

    public final String imageName;

    @DataBoundConstructor
    public DockerLabeledImage(String imageName, String labelString) {
        this.imageName = imageName;
        this.labelString = labelString;

        readResolve();
    }

    @SuppressWarnings("unchecked")
    public hudson.model.Descriptor<DockerLabeledImage> getDescriptor() {
        return Jenkins.getInstance().getDescriptor(getClass());
    }

    /** Initialize transient fields after deserialization. */
    protected Object readResolve() {
        _labels = Label.parse(this.labelString);
        return this;
    }

    public Set<LabelAtom> getLabels() {
        return _labels;
    }

    @Extension
    public static final class Descriptor extends hudson.model.Descriptor<DockerLabeledImage> {
        @Override
        public String getDisplayName() {
            return "Docker Labeled Image";
        }
    }
}
