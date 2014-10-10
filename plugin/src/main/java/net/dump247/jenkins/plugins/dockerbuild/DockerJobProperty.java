package net.dump247.jenkins.plugins.dockerbuild;

import hudson.Extension;
import hudson.model.Job;
import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;
import org.kohsuke.stapler.DataBoundConstructor;

import static com.google.common.base.Strings.nullToEmpty;

/**
 * Configure what docker image to run the job on.
 */
public class DockerJobProperty extends JobProperty<Job<?, ?>> {
    private final String _imageName;

    @DataBoundConstructor
    public DockerJobProperty(final String imageName) {
        _imageName = nullToEmpty(imageName).trim();
    }

    public String getImageName() {
        return _imageName;
    }

    @Extension
    public static final class Descriptor extends JobPropertyDescriptor {
        @Override
        public String getDisplayName() {
            return "Docker Build";
        }

        @Override
        public boolean isApplicable(Class<? extends Job> jobType) {
            return true;
        }
    }
}
