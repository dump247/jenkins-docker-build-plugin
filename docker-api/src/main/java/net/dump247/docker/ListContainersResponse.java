package net.dump247.docker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Response to a {@link ListContainersRequest}. */
public class ListContainersResponse {
    private List<ContainerInfo> _containers;

    public ListContainersResponse(List<ContainerInfo> containers) {
        _containers = Collections.unmodifiableList(new ArrayList<ContainerInfo>(containers));
    }

    public List<ContainerInfo> getContainers() {
        return _containers;
    }
}
