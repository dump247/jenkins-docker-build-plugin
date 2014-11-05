package net.dump247.jenkins.plugins.dockerbuild;

import com.google.common.collect.ImmutableList;
import hudson.Extension;
import jenkins.model.GlobalConfiguration;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.StaplerRequest;

import java.util.List;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Global configuration options for the plugin.
 * <p/>
 * The UI for this configuration is defined in <code>resources/FULL/CLASS/NAME/config.jelly</code>.
 * The configuration shows up in the jenkins server settings page.
 */
@Extension
public class DockerGlobalConfiguration extends GlobalConfiguration {
    private List<LabeledDockerImage> _labeledImages = ImmutableList.of();
    private String _jobRepositoryName = "docker/job";

    public DockerGlobalConfiguration() {
        // Classes deriving from GlobalConfiguration must call load() in their constructor
        load();
    }

    @Override
    public boolean configure(final StaplerRequest req, final JSONObject json) throws FormException {
        req.bindJSON(this, json);
        save();
        return true;
    }

    /**
     * Load the docker configuration options registered with the current Jenkins runtime.
     * <p/>
     * See {@link jenkins.model.GlobalConfiguration#all()}
     */
    public static DockerGlobalConfiguration get() {
        return GlobalConfiguration.all().get(DockerGlobalConfiguration.class);
    }

    public List<LabeledDockerImage> getLabeledImages() {
        return _labeledImages;
    }

    public void setLabeledImages(final List<LabeledDockerImage> labeledImages) {
        checkNotNull(labeledImages);
        _labeledImages = ImmutableList.copyOf(labeledImages);
    }

    public String getJobRepositoryName() {
        return _jobRepositoryName;
    }

    public void setJobRepositoryName(String jobRepositoryName) {
        checkNotNull(jobRepositoryName);
        _jobRepositoryName = jobRepositoryName;
    }
}
