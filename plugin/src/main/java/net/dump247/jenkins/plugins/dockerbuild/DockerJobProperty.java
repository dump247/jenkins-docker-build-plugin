package net.dump247.jenkins.plugins.dockerbuild;

import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;
import org.kohsuke.stapler.DataBoundConstructor;

public class DockerJobProperty extends JobProperty<AbstractProject<?, ?>> {
    public final boolean buildEnvironmentEnabled;
    public final boolean resetJob;

    @DataBoundConstructor
    public DockerJobProperty(boolean buildEnvironmentEnabled, boolean resetJob) {
        this.buildEnvironmentEnabled = buildEnvironmentEnabled;
        this.resetJob = resetJob;
    }

    public boolean resetJobEnabled() {
        return buildEnvironmentEnabled && resetJob;
    }

    @Extension
    public static class Descriptor extends JobPropertyDescriptor {
        @Override
        public String getDisplayName() {
            return DockerJobProperty.class.getSimpleName();
        }
    }
}
