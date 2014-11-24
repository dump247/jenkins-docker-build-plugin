package net.dump247.docker;

/** Exception thrown when a docker container does not exist. */
public class ContainerAlreadyStartedException extends DockerException {
    public ContainerAlreadyStartedException() {
    }

    public ContainerAlreadyStartedException(String message) {
        super(message);
    }

    public ContainerAlreadyStartedException(String message, Throwable cause) {
        super(message, cause);
    }
}
