package net.dump247.docker;

/** Exception thrown when a docker container does not exist. */
public class ContainerNotFoundException extends DockerException {
    public ContainerNotFoundException() {
    }

    public ContainerNotFoundException(String message) {
        super(message);
    }

    public ContainerNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
