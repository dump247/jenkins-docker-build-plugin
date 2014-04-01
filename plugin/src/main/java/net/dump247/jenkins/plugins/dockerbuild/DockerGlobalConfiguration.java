package net.dump247.jenkins.plugins.dockerbuild;

import com.google.common.collect.ImmutableList;
import hudson.Extension;
import jenkins.model.GlobalConfiguration;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.StaplerRequest;

import java.util.List;

import static com.google.common.base.Preconditions.checkNotNull;

/** Global configuration options for the plugin. */
@Extension
public class DockerGlobalConfiguration extends GlobalConfiguration {
    private List<DockerLabeledImage> _labeledImages = ImmutableList.of();
    private List<DockerCloud> _clouds = ImmutableList.of();

    public DockerGlobalConfiguration() {
        load();
    }

    @Override
    public boolean configure(final StaplerRequest req, final JSONObject json) throws FormException {
        req.bindJSON(this, json);
        save();

        return true;
    }

    public static DockerGlobalConfiguration get() {
        return GlobalConfiguration.all().get(DockerGlobalConfiguration.class);
    }

    public List<DockerLabeledImage> getLabeledImages() {
        return _labeledImages;
    }

    public void setLabeledImages(final List<DockerLabeledImage> labeledImages) {
        checkNotNull(labeledImages);
        _labeledImages = ImmutableList.copyOf(labeledImages);
    }

    public List<DockerCloud> getClouds() {
        return _clouds;
    }

    public void setClouds(final List<DockerCloud> clouds) {
        checkNotNull(clouds);
        _clouds = ImmutableList.copyOf(clouds);
    }
}
