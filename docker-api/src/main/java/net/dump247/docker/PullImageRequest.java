package net.dump247.docker;

/** Request to copy an existing image from the registry. */
public class PullImageRequest {
    private String _image;
    private String _tag = "";

    /**
     * Name of the image to pull.
     *
     * @return image name or null if no image name has been specified
     */
    public String getImage() {
        return _image;
    }

    public void setImage(final String image) {
        if (image == null) {
            throw new NullPointerException("image");
        }

        // TODO validate image name

        _image = image;
    }

    public PullImageRequest withImage(final String image) {
        setImage(image);
        return this;
    }

    /**
     * Name of the tag to pull or empty string for the default.
     *
     * @return tag name
     */
    public String getTag() {
        return _tag;
    }

    public void setTag(final String tag) {
        if (tag == null) {
            throw new NullPointerException("tag");
        }

        // TODO validate image name

        _tag = tag;
    }

    public PullImageRequest withTag(final String tag) {
        setTag(tag);
        return this;
    }
}
