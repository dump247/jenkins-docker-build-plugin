package net.dump247.docker;

/**
 * Request to remove an image from the filesystem.
 */
public class RemoveImageRequest {
    private String _image;

    /**
     * Name of the image to remove or null if not yet provided.
     *
     * @return image name
     */
    public String getImage() {
        return _image;
    }

    public void setImage(final String image) {
        if (image == null) {
            throw new NullPointerException("image");
        }

        // TODO validate containerId

        _image = image;
    }

    public RemoveImageRequest withImage(final String image) {
        setImage(image);
        return this;
    }
}
