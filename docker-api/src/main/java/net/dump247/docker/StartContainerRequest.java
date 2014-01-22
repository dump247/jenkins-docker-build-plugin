package net.dump247.docker;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Options for starting a docker container.
 * <p/>
 * An important note about bindings: the container path must be declared a volume
 * in the {@link CreateContainerRequest}. Otherwise, the binding will not be created
 * and no error is reported.
 */
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
public class StartContainerRequest {
    private String _containerId;
    private List<DirectoryBinding> _bindings;

    /**
     * ID of the container to start.
     *
     * @return container id
     */
    public String getContainerId() {
        return _containerId;
    }

    @JsonIgnore
    public void setContainerId(final String containerId) {
        if (containerId == null) {
            throw new NullPointerException("containerId");
        }

        _containerId = containerId;
    }

    public StartContainerRequest withContainerId(final String containerId) {
        setContainerId(containerId);
        return this;
    }

    /**
     * Directories from the host machine bound to paths in the container.
     *
     * @return directory bindings
     */
    public List<DirectoryBinding> getBindings() {
        if (_bindings == null) {
            _bindings = new ArrayList<DirectoryBinding>();
        }

        return _bindings;
    }

    @JsonProperty("Binds")
    @JsonSerialize(using = BindSerializer.class, include = JsonSerialize.Inclusion.NON_DEFAULT)
    public void setBindings(final List<DirectoryBinding> bindings) {
        if (bindings == null) {
            throw new NullPointerException("bindings");
        }

        _bindings = new ArrayList<DirectoryBinding>(bindings);
    }

    public StartContainerRequest withBindings(final List<DirectoryBinding> bindings) {
        setBindings(bindings);
        return this;
    }

    public StartContainerRequest withBindings(DirectoryBinding... bindings) {
        setBindings(Arrays.asList(bindings));
        return this;
    }

    public StartContainerRequest withBinding(final String hostPath, final String containerPath) {
        getBindings().add(new DirectoryBinding(hostPath, containerPath));
        return this;
    }

    public StartContainerRequest withBinding(final String hostPath, final String containerPath, final DirectoryBinding.Access access) {
        getBindings().add(new DirectoryBinding(hostPath, containerPath, access));
        return this;
    }

    private static class BindSerializer extends JsonSerializer<List<DirectoryBinding>> {
        @Override
        public void serialize(final List<DirectoryBinding> value, final JsonGenerator jgen, final SerializerProvider provider) throws IOException {
            jgen.writeStartArray();

            for (DirectoryBinding binding : value) {
                jgen.writeString(binding.getHostPath() + ":" + binding.getContainerPath() + ":" + binding.getAccess().toApiString());
            }

            jgen.writeEndArray();
        }

        @Override
        public boolean isEmpty(final List<DirectoryBinding> value) {
            return value == null || value.isEmpty();
        }
    }
}
