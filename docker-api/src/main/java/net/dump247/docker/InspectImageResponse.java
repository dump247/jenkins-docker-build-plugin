package net.dump247.docker;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Detailed information about an image.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class InspectImageResponse {
    private String _id;
    private String _parentId;
    private ContainerConfig _containerConfig;

    public String getId() {
        return _id;
    }

    @JsonProperty("id")
    public void setId(String id) {
        _id = id;
    }

    public String getParentId() {
        return _parentId;
    }

    @JsonProperty("parent")
    public void setParentId(String parentId) {
        _parentId = parentId;
    }

    public ContainerConfig getContainerConfig() {
        return _containerConfig;
    }

    @JsonProperty("container_config")
    public void setContainerConfig(ContainerConfig config) {
        _containerConfig = config;
    }
}
