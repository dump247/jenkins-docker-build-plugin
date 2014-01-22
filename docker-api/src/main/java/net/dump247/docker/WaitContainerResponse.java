package net.dump247.docker;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Response from waiting for a container to stop. */
public class WaitContainerResponse {
    private int _statusCode;

    /**
     * Exit code from the container command.
     *
     * @return container command exit code
     */
    public int getStatusCode() {
        return _statusCode;
    }

    @JsonProperty("StatusCode")
    public void setStatusCode(final int statusCode) {
        _statusCode = statusCode;
    }
}
