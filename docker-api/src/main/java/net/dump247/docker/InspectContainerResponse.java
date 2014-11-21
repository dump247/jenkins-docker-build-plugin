package net.dump247.docker;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Low-level information about a container.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class InspectContainerResponse {
    private String _id;
    private String _imageId;
    private ContainerConfig _config = new ContainerConfig();

    public ContainerConfig getConfig() {
        return _config;
    }

    @JsonProperty("Config")
    public void setConfig(ContainerConfig config) {
        _config = config;
    }

    public String getImageId() {
        return _imageId;
    }

    @JsonProperty("Image")
    public void setImageId(String imageId) {
        _imageId = imageId;
    }

    public String getId() {
        return _id;
    }

    @JsonProperty("ID")
    public void setId(String id) {
        _id = id;
    }

}
