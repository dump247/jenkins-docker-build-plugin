package net.dump247.docker;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Response to committing a container to a new image. */
public class CommitContainerResponse {
    private String _imageId;

    /**
     * ID of the new image.
     *
     * @return image id
     */
    public String getImageId() {
        return _imageId;
    }

    @JsonProperty("Id")
    public void setImageId(final String imageId) {
        _imageId = imageId;
    }
}
