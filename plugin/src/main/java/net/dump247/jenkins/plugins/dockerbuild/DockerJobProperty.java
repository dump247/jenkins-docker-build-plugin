package net.dump247.jenkins.plugins.dockerbuild;

import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;
import org.kohsuke.stapler.DataBoundConstructor;

public class DockerJobProperty extends JobProperty<AbstractProject<?, ?>> {
    public final boolean commitJobImage;

    @DataBoundConstructor
    public DockerJobProperty(boolean commitJobImage) {
        this.commitJobImage = commitJobImage;
    }

    @Extension
    public static class Descriptor extends JobPropertyDescriptor {
        @Override
        public String getDisplayName() {
            return DockerJobProperty.class.getSimpleName();
        }
    }
}
