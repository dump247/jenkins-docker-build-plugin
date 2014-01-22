package net.dump247.docker;

import java.io.IOException;

/** Exception thrown when an error occurs communicating with docker. */
public class DockerException extends IOException {
    public DockerException() {
    }

    public DockerException(String message) {
        super(message);
    }

    public DockerException(String message, Throwable cause) {
        super(message, cause);
    }
}
