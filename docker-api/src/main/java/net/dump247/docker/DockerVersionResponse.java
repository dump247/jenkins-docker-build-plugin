package net.dump247.docker;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Docker version information. */
public class DockerVersionResponse {
    private String _dockerVersion;
    private String _gitCommit;
    private String _goVersion;

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

    @Override
    public String toString() {
        return "DockerVersionResponse{" +
                "dockerVersion=" + _dockerVersion + "; " +
                "gitCommit=" + _gitCommit + "; " +
                "goVersion=" + _goVersion +
                "}";
    }
}
