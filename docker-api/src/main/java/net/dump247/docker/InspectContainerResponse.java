package net.dump247.docker;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.util.Collections;
import java.util.List;

/**
 * Low-level information about a container.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class InspectContainerResponse {
    private String _id;
    private String _imageId;
    private ContainerConfig _config = new ContainerConfig();

    public ContainerConfig getConfig() {
        return _config;
    }

    @JsonProperty("Config")
    public void setConfig(ContainerConfig config) {
        _config = config;
    }

    public String getImageId() {
        return _imageId;
    }

    @JsonProperty("Image")
    public void setImageId(String imageId) {
        _imageId = imageId;
    }

    public String getId() {
        return _id;
    }

    @JsonProperty("ID")
    public void setId(String id) {
        _id = id;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ContainerConfig {
        private boolean _attachStdin;
        private boolean _attachStdout;
        private boolean _attachStderr;
        private boolean _tty;
        private boolean _openStdin;
        private boolean _stdinOnce;
        private List<String> _command = Collections.emptyList();
        private List<ContainerVolume> _volumes = Collections.emptyList();

        public boolean isAttachStdin() {
            return _attachStdin;
        }

        @JsonProperty("AttachStdin")
        public void setAttachStdin(boolean attachStdin) {
            _attachStdin = attachStdin;
        }

        public boolean isAttachStdout() {
            return _attachStdout;
        }

        @JsonProperty("AttachStdout")
        public void setAttachStdout(boolean attachStdout) {
            _attachStdout = attachStdout;
        }

        public boolean isAttachStderr() {
            return _attachStderr;
        }

        @JsonProperty("AttachStderr")
        public void setAttachStderr(boolean attachStderr) {
            _attachStderr = attachStderr;
        }

        public boolean isTty() {
            return _tty;
        }

        @JsonProperty("Tty")
        public void setTty(boolean tty) {
            _tty = tty;
        }

        public boolean isOpenStdin() {
            return _openStdin;
        }

        @JsonProperty("OpenStdin")
        public void setOpenStdin(boolean openStdin) {
            _openStdin = openStdin;
        }

        public boolean isStdinOnce() {
            return _stdinOnce;
        }

        @JsonProperty("StdinOnce")
        public void setStdinOnce(boolean stdinOnce) {
            _stdinOnce = stdinOnce;
        }

        public List<String> getCommand() {
            return _command;
        }

        @JsonProperty("Cmd")
        public void setCommand(List<String> command) {
            _command = command;
        }

        public List<ContainerVolume> getVolumes() {
            return _volumes;
        }

        @JsonProperty("Volumes")
        @JsonDeserialize(using = CreateContainerRequest.VolumeDeserializer.class)
        @JsonSerialize(using = CreateContainerRequest.VolumeSerializer.class, include = JsonSerialize.Inclusion.NON_DEFAULT)
        public void setVolumes(List<ContainerVolume> volumes) {
            _volumes = volumes;
        }
    }
}
