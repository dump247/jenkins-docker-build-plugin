package net.dump247.docker;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Docker version information. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class DockerVersionResponse {
    private String _dockerVersion;
    private String _gitCommit;
    private String _goVersion;
    private String _arch;
    private String _kernelVersion;
    private String _os;

    public String getVersion() {
        return _dockerVersion;
    }

    @JsonProperty("Version")
    public void setVersion(final String version) {
        _dockerVersion = version;
    }

    public String getGitCommit() {
        return _gitCommit;
    }

    @JsonProperty("GitCommit")
    public void setGitCommit(final String gitCommit) {
        _gitCommit = gitCommit;
    }

    public String getGoVersion() {
        return _goVersion;
    }

    @JsonProperty("GoVersion")
    public void setGoVersion(final String goVersion) {
        _goVersion = goVersion;
    }

    public String getArch() {
        return _arch;
    }

    @JsonProperty("Arch")
    public void setArch(final String arch) {
        _arch = arch;
    }

    public String getKernelVersion() {
        return _kernelVersion;
    }

    @JsonProperty("KernelVersion")
    public void setKernelVersion(final String kernelVersion) {
        _kernelVersion = kernelVersion;
    }

    public String getOs() {
        return _os;
    }

    @JsonProperty("Os")
    public void setOs(final String os) {
        _os = os;
    }

    @Override
    public String toString() {
        return "DockerVersionResponse{" +
                "dockerVersion=" + _dockerVersion + "; " +
                "gitCommit=" + _gitCommit + "; " +
                "goVersion=" + _goVersion + "; " +
                "arch=" + _arch + "; " +
                "kernelVersion=" + _kernelVersion + "; " +
                "os=" + _os +
                "}";
    }
}
