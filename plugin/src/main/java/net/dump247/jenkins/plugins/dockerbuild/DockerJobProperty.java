package net.dump247.jenkins.plugins.dockerbuild;

import com.google.common.collect.ImmutableMap;
import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;
import hudson.model.Label;
import hudson.util.FormValidation;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.lang.String.format;
import static java.util.Collections.unmodifiableSet;
import static net.dump247.jenkins.plugins.dockerbuild.ConfigUtil.splitConfigLines;

public class DockerJobProperty extends JobProperty<AbstractProject<?, ?>> {
    private static final Logger LOG = Logger.getLogger(DockerJobProperty.class.getName());

    public final boolean buildEnvironmentEnabled;
    public final boolean resetJob;
    public final String environmentVarString;

    private transient Map<String, String> _environmentVars;

    @DataBoundConstructor
    public DockerJobProperty(boolean buildEnvironmentEnabled, boolean resetJob, String environmentVarString) {
        this.buildEnvironmentEnabled = buildEnvironmentEnabled;
        this.resetJob = resetJob;
        this.environmentVarString = environmentVarString;

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

    private static Map<String, String> parseEnvVars(String content) {
        ImmutableMap.Builder<String, String> vars = ImmutableMap.builder();

        for (ConfigUtil.ConfigLine line : splitConfigLines(content)) {
            String[] parts = line.value.split("=", 2);
            String name = parts[0].trim();
            String value = parts.length > 1 ? parts[1].trim() : "";

            if (name.length() == 0) {
                throw new IllegalArgumentException(format("Environment variable name is required (line %d): %s", line.lineNum, line));
            }

            vars.put(name, value);
        }

        return vars.build();
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
