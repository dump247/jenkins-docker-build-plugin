package net.dump247.docker;

import java.io.IOException;
import java.io.InputStream;

/** Result of attaching to a container. */
public class AttachResponse {
    public static final InputStream EMPTY_STREAM = new EmptyInputStream();

    private InputStream _stdout = EMPTY_STREAM;
    private InputStream _stderr = EMPTY_STREAM;

    public InputStream getStdout() {
        return _stdout;
    }

    public void setStdout(final InputStream stdout) {
        if (stdout == null) {
            throw new NullPointerException("stdout");
        }

        _stdout = stdout;
    }

    public AttachResponse withStdout(final InputStream stdout) {
        setStdout(stdout);
        return this;
    }

    public InputStream getStderr() {
        return _stderr;
    }

    public void setStderr(final InputStream stderr) {
        if (stderr == null) {
            throw new NullPointerException("stderr");
        }

        _stderr = stderr;
    }

    public AttachResponse withStderr(final InputStream stderr) {
        setStderr(stderr);
        return this;
    }

    private static class EmptyInputStream extends InputStream {
        @Override
        public int read() throws IOException {
            return -1;
        }
    }
}
