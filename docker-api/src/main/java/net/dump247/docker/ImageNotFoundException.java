package net.dump247.docker;

/** Exception thrown when a docker image does not exist. */
public class ImageNotFoundException extends DockerException {
    public ImageNotFoundException() {
    }

    public ImageNotFoundException(String message) {
        super(message);
    }

    public ImageNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
