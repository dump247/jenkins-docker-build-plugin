package net.dump247.docker;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/** Result of creating a new docker container. */
public class CreateContainerResponse {
    private String _containerId;
    private List<String> _warnings;

    /**
     * ID of the new container.
     *
     * @return container id
     */
    public String getContainerId() {
        return _containerId;
    }

    @JsonProperty("Id")
    public void setContainerId(final String id) {
        _containerId = id;
    }

    /**
     * Warnings that resulted from creating the new container.
     *
     * @return list of warnings
     */
    public List<String> getWarnings() {
        if (_warnings == null) {
            _warnings = new ArrayList<String>();
        }

        return _warnings;
    }

    @JsonProperty("Warnings")
    public void setWarnings(final List<String> warnings) {
        _warnings = warnings;
    }
}
