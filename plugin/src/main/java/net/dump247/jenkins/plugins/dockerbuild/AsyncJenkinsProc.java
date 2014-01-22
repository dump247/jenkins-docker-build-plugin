package net.dump247.jenkins.plugins.dockerbuild;

import hudson.Proc;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.Future;

/**
 * Jenkins {@link hudson.Proc} adapter for a {@link Future}.
 * <p/>
 * The output of the future should be the exit code for the process.
 */
public class AsyncJenkinsProc extends Proc {
    private final Future<Integer> _future;
    private final InputStream _stdout;
    private final InputStream _stderr;
    private final OutputStream _stdin;

    /**
     * Initialize a new instance.
     * <p/>
     * Set stdout, stderr, and stdin to null.
     *
     * @param future future to operate on
     */
    public AsyncJenkinsProc(Future<Integer> future) {
        this(future, null, null, null);
    }

    /**
     * Initialize a new instance.
     *
     * @param future future to operate on
     * @param stdout standard output stream or null
     * @param stderr standard error stream or null
     * @param stdin  standard input stream or null
     */
    public AsyncJenkinsProc(Future<Integer> future, InputStream stdout, InputStream stderr, OutputStream stdin) {
        if (future == null) {
            throw new NullPointerException("future");
        }

        _future = future;
        _stdout = stdout;
        _stderr = stderr;
        _stdin = stdin;
    }

    @Override
    public boolean isAlive() throws IOException, InterruptedException {
        return !_future.isDone();
    }

    @Override
    public void kill() throws IOException, InterruptedException {
        _future.cancel(true);
        join();
    }

    @Override
    public int join() throws IOException, InterruptedException {
        try {
            return _future.get();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public InputStream getStdout() {
        return _stdout;
    }

    @Override
    public InputStream getStderr() {
        return _stderr;
    }

    @Override
    public OutputStream getStdin() {
        return _stdin;
    }
}
