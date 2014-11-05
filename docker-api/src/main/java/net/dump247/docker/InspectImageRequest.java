package net.dump247.docker;

/**
 * Request to get detailed information about an image.
 */
public class InspectImageRequest {
    private String _imageName;

    public String getImageName() {
        return _imageName;
    }

    public void setImageName(String imageName) {
        if (imageName == null) {
            throw new NullPointerException("imageName");
        }

        _imageName = imageName;
    }

    public InspectImageRequest withImageName(String imageName) {
        setImageName(imageName);
        return this;
    }
}
