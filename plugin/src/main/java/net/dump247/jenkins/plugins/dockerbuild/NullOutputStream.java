package net.dump247.jenkins.plugins.dockerbuild;

import java.io.IOException;
import java.io.OutputStream;

/** All data written to this output stream is dropped. */
public class NullOutputStream extends OutputStream {
    public static final OutputStream INSTANCE = new NullOutputStream();

    private NullOutputStream() {
    }

    @Override
    public void write(final int i) throws IOException {
        // Do nothing
    }

    @Override
    public void write(final byte[] bytes, final int i, final int i2) throws IOException {
        // Do nothing
    }
}
