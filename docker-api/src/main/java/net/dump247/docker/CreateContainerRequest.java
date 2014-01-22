package net.dump247.docker;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Options for creating a new docker container. */
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
public class CreateContainerRequest {
    private String _hostname = "";
    private String _user = "";
    private long _memoryLimit;
    private long _swapLimit;
    private boolean _attachStdin;
    private boolean _attachStdout;
    private boolean _attachStderr;
    private boolean _privileged;
    private boolean _tty;
    private boolean _openStdin;
    private boolean _stdinOnce;
    private Map<String, String> _environment;
    private List<String> _command;
    private String _image;
    private String _workingDir = "";
    private List<ContainerVolume> _volumes;

    /**
     * Container hostname.
     *
     * @return hostname
     */
    public String getHostname() {
        return _hostname;
    }

    @JsonProperty("Hostname")
    public void setHostname(final String hostname) {
        if (hostname == null) {
            throw new NullPointerException("hostname");
        }

        _hostname = hostname;
    }

    public CreateContainerRequest withHostname(final String hostname) {
        setHostname(hostname);
        return this;
    }

    /**
     * Username or UID.
     *
     * @return user
     */
    public String getUser() {
        return _user;
    }

    @JsonProperty("User")
    public void setUser(final String user) {
        if (user == null) {
            throw new NullPointerException("user");
        }

        _user = user;
    }

    public CreateContainerRequest withUser(final String user) {
        setUser(user);
        return this;
    }

    /**
     * Maximum amount of memory, in bytes, the container can use or 0 if the
     * container has no limit.
     *
     * @return memory limit or 0 for none
     */
    public long getMemoryLimit() {
        return _memoryLimit;
    }

    @JsonProperty("Memory")
    public void setMemoryLimit(final long memoryLimit) {
        if (memoryLimit < 0) {
            throw new IllegalArgumentException("memoryLimit must be greater than or equal to 0");
        }

        _memoryLimit = memoryLimit;
    }

    public CreateContainerRequest withMemoryLimit(final long memoryLimit) {
        setMemoryLimit(memoryLimit);
        return this;
    }

    /**
     * Maximum amount of swap space, in bytes, the container can use or 0 if the
     * container has no limit.
     *
     * @return swap limit or 0 for none
     */
    public long getSwapLimit() {
        return _swapLimit;
    }

    @JsonProperty("MemorySwap")
    public void setSwapLimit(final long swapLimit) {
        if (swapLimit < 0) {
            throw new IllegalArgumentException("swapLimit must be greater than or equal to 0");
        }

        _swapLimit = swapLimit;
    }

    public CreateContainerRequest withSwapLimit(long swapLimit) {
        setMemoryLimit(swapLimit);
        return this;
    }

    public boolean isAttachStdin() {
        return _attachStdin;
    }

    @JsonProperty("AttachStdin")
    public void setAttachStdin(final boolean attachStdin) {
        _attachStdin = attachStdin;
    }

    public CreateContainerRequest withAttachStdin(final boolean attachStdin) {
        setAttachStdin(attachStdin);
        return this;
    }

    public boolean isAttachStdout() {
        return _attachStdout;
    }

    @JsonProperty("AttachStdout")
    public void setAttachStdout(final boolean attachStdout) {
        _attachStdout = attachStdout;
    }

    public CreateContainerRequest withAttachStdout(final boolean attachStdout) {
        setAttachStdout(attachStdout);
        return this;
    }

    public boolean isAttachStderr() {
        return _attachStderr;
    }

    @JsonProperty("AttachStderr")
    public void setAttachStderr(final boolean attachStderr) {
        _attachStderr = attachStderr;
    }

    public CreateContainerRequest withAttachStderr(final boolean attachStderr) {
        setAttachStderr(attachStderr);
        return this;
    }

    public boolean isPrivileged() {
        return _privileged;
    }

    @JsonProperty("Privileged")
    public void setPrivileged(final boolean privileged) {
        _privileged = privileged;
    }

    public CreateContainerRequest withPrivileged(final boolean privileged) {
        setPrivileged(privileged);
        return this;
    }

    public boolean isTty() {
        return _tty;
    }

    @JsonProperty("Tty")
    public void setTty(final boolean tty) {
        _tty = tty;
    }

    public CreateContainerRequest withTty(final boolean tty) {
        setTty(tty);
        return this;
    }

    public boolean isOpenStdin() {
        return _openStdin;
    }

    @JsonProperty("OpenStdin")
    public void setOpenStdin(final boolean openStdin) {
        _openStdin = openStdin;
    }

    public CreateContainerRequest withOpenStdin(final boolean openStdin) {
        setOpenStdin(openStdin);
        return this;
    }

    public boolean isStdinOnce() {
        return _stdinOnce;
    }

    @JsonProperty("StdinOnce")
    public void setStdinOnce(final boolean stdinOnce) {
        _stdinOnce = stdinOnce;
    }

    public CreateContainerRequest withStdinOnce(final boolean stdinOnce) {
        setStdinOnce(stdinOnce);
        return this;
    }

    public Map<String, String> getEnvironment() {
        if (_environment == null) {
            _environment = new HashMap<String, String>();
        }

        return _environment;
    }

    @JsonProperty("Env")
    @JsonSerialize(using = EnvSerializer.class, include = JsonSerialize.Inclusion.NON_DEFAULT)
    public void setEnvironment(final Map<String, String> environment) {
        if (environment == null) {
            throw new NullPointerException("environment");
        }

        _environment = new HashMap<String, String>(environment);
    }

    public CreateContainerRequest withEnvironment(final Map<String, String> environment) {
        setEnvironment(environment);
        return this;
    }

    public CreateContainerRequest withEnvironmentVar(String key, String value) {
        getEnvironment().put(key, value);
        return this;
    }

    public List<String> getCommand() {
        if (_command == null) {
            _command = new ArrayList<String>();
        }

        return _command;
    }

    @JsonProperty("Cmd")
    public void setCommand(final List<String> command) {
        if (command == null) {
            throw new NullPointerException("command");
        }

        if (command.size() == 0) {
            throw new IllegalArgumentException("A command is required");
        }

        _command = new ArrayList<String>(command);
    }

    public CreateContainerRequest withCommand(final List<String> command) {
        setCommand(command);
        return this;
    }

    public CreateContainerRequest withCommand(String... command) {
        setCommand(Arrays.asList(command));
        return this;
    }

    public String getImage() {
        return _image;
    }

    @JsonProperty("Image")
    public void setImage(final String image) {
        if (image == null) {
            throw new NullPointerException("image");
        }

        if (image.length() == 0) {
            throw new IllegalArgumentException("image can not be empty");
        }

        _image = image;
    }

    public CreateContainerRequest withImage(final String image) {
        setImage(image);
        return this;
    }

    public String getWorkingDir() {
        return _workingDir;
    }

    @JsonProperty("WorkingDir")
    public void setWorkingDir(final String workingDir) {
        if (workingDir == null) {
            throw new NullPointerException("workingDir");
        }

        _workingDir = workingDir;
    }

    public CreateContainerRequest withWorkingDir(final String workingDir) {
        setWorkingDir(workingDir);
        return this;
    }

    public List<ContainerVolume> getVolumes() {
        if (_volumes == null) {
            _volumes = new ArrayList<ContainerVolume>();
        }

        return _volumes;
    }

    @JsonProperty("Volumes")
    @JsonSerialize(using = VolumeSerializer.class, include = JsonSerialize.Inclusion.NON_DEFAULT)
    public void setVolumes(final List<ContainerVolume> volumes) {
        if (volumes == null) {
            throw new NullPointerException("volumes");
        }

        _volumes = new ArrayList<ContainerVolume>(volumes);
    }

    public CreateContainerRequest withVolumes(final List<ContainerVolume> volumes) {
        setVolumes(volumes);
        return this;
    }

    public CreateContainerRequest withVolumes(ContainerVolume... volumes) {
        setVolumes(Arrays.asList(volumes));
        return this;
    }

    public CreateContainerRequest withVolumes(final String... paths) {
        if (paths == null) {
            throw new NullPointerException("paths");
        }

        List<ContainerVolume> volumes = new ArrayList<ContainerVolume>(paths.length);

        for (String path : paths) {
            volumes.add(new ContainerVolume(path));
        }

        setVolumes(volumes);
        return this;
    }

    private static class EnvSerializer extends JsonSerializer<Map<String, String>> {
        @Override
        public void serialize(final Map<String, String> value, final JsonGenerator jgen, final SerializerProvider provider) throws IOException {
            if (value == null) {
                jgen.writeNull();
                return;
            }

            jgen.writeStartArray();

            for (Map.Entry<String, String> entry : value.entrySet()) {
                jgen.writeString(entry.getKey() + "=" + entry.getValue());
            }

            jgen.writeEndArray();
        }

        @Override
        public boolean isEmpty(final Map<String, String> value) {
            return value == null || value.isEmpty();
        }
    }

    private static class VolumeSerializer extends JsonSerializer<List<ContainerVolume>> {
        @Override
        public void serialize(final List<ContainerVolume> value, final JsonGenerator jgen, final SerializerProvider provider) throws IOException, JsonProcessingException {
            if (value == null) {
                jgen.writeNull();
                return;
            }

            jgen.writeStartObject();

            for (ContainerVolume volume : value) {
                // For some reason, the API requires an empty object as the value.
                jgen.writeObjectFieldStart(volume.getPath());
                jgen.writeEndObject();
            }

            jgen.writeEndObject();
        }

        @Override
        public boolean isEmpty(final List<ContainerVolume> value) {
            return value == null || value.isEmpty();
        }
    }
}
