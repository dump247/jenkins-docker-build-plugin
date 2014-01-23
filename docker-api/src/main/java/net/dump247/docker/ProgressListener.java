package net.dump247.docker;

/** Receive progress events from docker operations. */
public interface ProgressListener {
    /** Write all normal progress messages to stdout and error messages to stderr. */
    static final ProgressListener STDOUT = new ProgressWriter(System.out);

    /** Write all normal progress messages to stdout and error messages to stderr. */
    static final ProgressListener STDERR = new ProgressWriter(System.err);

    /** Write all normal progress messages to stdout and error messages to stderr. */
    static final ProgressListener OUTPUT = new ProgressWriter(System.out, System.err);

    /** Drop all progress messages. */
    static final ProgressListener NULL = new ProgressListener() {
        @Override
        public void progress(final ProgressEvent message) {
            // Do nothing
        }
    };

    void progress(ProgressEvent message);
}
