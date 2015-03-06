package com.github.dump247.jenkins.plugins.dockerjob;

import com.google.common.collect.ImmutableMap;
import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;
import hudson.util.FormValidation;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.github.dump247.jenkins.plugins.dockerjob.util.ConfigUtil.parseEnvVars;
import static java.lang.String.format;

/**
 * Job-specific options.
 * <p/>
 * These options are included in each job configuration page.
 */
public class DockerJobProperty extends JobProperty<AbstractProject<?, ?>> {
    private static final Logger LOG = Logger.getLogger(DockerJobProperty.class.getName());

    public final boolean buildEnvironmentEnabled;
    public final boolean resetJob;
    public final String environmentVarString;
    public final String imageName;

    private transient Map<String, String> _environmentVars;

    @DataBoundConstructor
    public DockerJobProperty(boolean buildEnvironmentEnabled, boolean resetJob, String environmentVarString, String imageName) {
        this.buildEnvironmentEnabled = buildEnvironmentEnabled;
        this.resetJob = resetJob;
        this.environmentVarString = environmentVarString;
        this.imageName = imageName;

        this.readResolve();
    }

    /**
     * Initialize transient fields after deserialization.
     */
    protected Object readResolve() {
        _environmentVars = ImmutableMap.of();

        if (this.buildEnvironmentEnabled) {
            try {
                _environmentVars = parseEnvVars(environmentVarString);
            } catch (IllegalArgumentException ex) {
                LOG.log(Level.WARNING, format("Invalid environment vars in job %s", this.owner.getFullDisplayName()), ex);
            }
        }

        return this;
    }

    public boolean resetJobEnabled() {
        return buildEnvironmentEnabled && resetJob;
    }

    public Map<String, String> getEnvironmentVars() {
        return _environmentVars;
    }

    @Extension
    public static class Descriptor extends JobPropertyDescriptor {
        @Override
        public String getDisplayName() {
            return DockerJobProperty.class.getSimpleName();
        }

        public FormValidation doCheckEnvironmentVarString(@QueryParameter String value) {
            try {
                parseEnvVars(value);
                return FormValidation.ok();
            } catch (Exception ex) {
                return FormValidation.error(ex.getMessage());
            }
        }
    }
}
