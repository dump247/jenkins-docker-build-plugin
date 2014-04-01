package net.dump247.docker;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** System-wide information. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SystemInfoResponse {
    private int _numContainers;
    private int _numImages;

    public int getNumContainers() {
        return _numContainers;
    }

    @JsonProperty("Containers")
    public void setNumContainers(final int numContainers) {
        _numContainers = numContainers;
    }

    public int getNumImages() {
        return _numImages;
    }

    @JsonProperty("Images")
    public void setNumImages(final int numImages) {
        _numImages = numImages;
    }
}
