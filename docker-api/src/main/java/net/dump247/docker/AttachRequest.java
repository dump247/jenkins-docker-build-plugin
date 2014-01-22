package net.dump247.docker;

/** Request to attach to a container. */
public class AttachRequest {
    private String _containerId;
    private boolean _logs = true;
    private boolean _stream = true;
    private boolean _stdoutIncluded = true;
    private boolean _stderrIncluded = true;

    public String getContainerId() {
        return _containerId;
    }

    public void setContainerId(final String containerId) {
        if (containerId == null) {
            throw new NullPointerException("containerId");
        }

        // TODO validate containerId

        _containerId = containerId;
    }

    public AttachRequest withContainerId(final String containerId) {
        setContainerId(containerId);
        return this;
    }

    public boolean isLogs() {
        return _logs;
    }

    public void setLogs(final boolean logs) {
        _logs = logs;
    }

    public AttachRequest withLogs(final boolean logs) {
        setLogs(logs);
        return this;
    }

    public boolean isStream() {
        return _stream;
    }

    public void setStream(final boolean stream) {
        _stream = stream;
    }

    public AttachRequest withStream(final boolean stream) {
        setStream(stream);
        return this;
    }

    public boolean isStdoutIncluded() {
        return _stdoutIncluded;
    }

    public void setStdoutIncluded(final boolean stdoutIncluded) {
        _stdoutIncluded = stdoutIncluded;
    }

    public AttachRequest withStdoutIncluded(final boolean stdoutIncluded) {
        setStdoutIncluded(stdoutIncluded);
        return this;
    }

    public boolean isStderrIncluded() {
        return _stderrIncluded;
    }

    public void setStderrIncluded(final boolean stderrIncluded) {
        _stderrIncluded = stderrIncluded;
    }

    public AttachRequest withStderrIncluded(final boolean stderrIncluded) {
        setStderrIncluded(stderrIncluded);
        return this;
    }
}
