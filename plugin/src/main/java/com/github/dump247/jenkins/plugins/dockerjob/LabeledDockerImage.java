package com.github.dump247.jenkins.plugins.dockerjob;

import hudson.Extension;
import hudson.model.Describable;
import hudson.model.Label;
import hudson.model.labels.LabelAtom;
import hudson.util.FormValidation;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import java.util.Map;
import java.util.Set;

import static com.github.dump247.jenkins.plugins.dockerjob.util.ConfigUtil.parseEnvVars;
import static com.google.common.base.Strings.nullToEmpty;

/**
 * Labels applied to a specific docker image.
 * <p/>
 * This is used to configure an image that can be selected using label expressions
 * in the job configuration. If a job's labels match, the job is run on the given docker image.
 */
public class LabeledDockerImage implements Describable<LabeledDockerImage> {
    public final String labelString;
    public final String environmentVarString;
    public final String imageName;

    private transient Set<LabelAtom> _labels;
    private transient Map<String, String> _environmentVars;

    @DataBoundConstructor
    public LabeledDockerImage(String imageName, String labelString, String environmentVarString) {
        this.imageName = imageName;
        this.labelString = labelString;
        this.environmentVarString = environmentVarString;

        this.readResolve();
    }

    protected Object readResolve() {
        _environmentVars = parseEnvVars(environmentVarString);
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public hudson.model.Descriptor<LabeledDockerImage> getDescriptor() {
        return Jenkins.getInstance().getDescriptor(getClass());
    }

    public Map<String, String> getEnvironmentVars() {
        return _environmentVars;
    }

    public Set<LabelAtom> getLabels() {
        if (_labels == null) {
            // Do not do this in readResolve as it can result in a recursive dependency load that
            // makes jenkins startup slow and unstable.
            _labels = Label.parse(this.labelString);
        }

        return _labels;
    }

    @Extension
    public static final class Descriptor extends hudson.model.Descriptor<LabeledDockerImage> {
        @Override
        public String getDisplayName() {
            return "Labeled Docker Image";
        }

        public FormValidation doCheckLabelString(@QueryParameter String value) {
            value = nullToEmpty(value).trim();

            if (value.length() == 0) {
                return FormValidation.error("Required");
            }

            try {
                Label.parse(value);
                return FormValidation.ok();
            } catch (Throwable ex) {
                return FormValidation.error("Invalid labels: %s", ex.getMessage());
            }
        }

        public FormValidation doCheckImageName(@QueryParameter String value) {
            return nullToEmpty(value).trim().length() == 0
                    ? FormValidation.error("Required")
                    : FormValidation.ok();
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
