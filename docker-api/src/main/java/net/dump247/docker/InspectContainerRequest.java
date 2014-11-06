package net.dump247.docker;

/**
 * Request to low-level information about a container.
 */
public class InspectContainerRequest {
    private String _id;

    public String getId() {
        return _id;
    }

    public void setId(String id) {
        _id = id;
    }

    public InspectContainerRequest withId(String id) {
        setId(id);
        return this;
    }
}
