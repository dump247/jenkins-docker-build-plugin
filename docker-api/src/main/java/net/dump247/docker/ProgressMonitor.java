package net.dump247.docker;

/** Receive progress events from docker operations. */
public interface ProgressMonitor {
    /** Write all normal progress messages to stdout and error messages to stderr. */
    static final ProgressMonitor STDOUT = new ProgressWriter(System.out);

    /** Write all normal progress messages to stdout and error messages to stderr. */
    static final ProgressMonitor STDERR = new ProgressWriter(System.err);

    /** Write all normal progress messages to stdout and error messages to stderr. */
    static final ProgressMonitor OUTPUT = new ProgressWriter(System.out, System.err);

    /** Drop all progress messages. */
    static final ProgressMonitor NULL = new ProgressMonitor() {
        @Override
        public void progress(final ProgressMessage message) {
            // Do nothing
        }
    };

    void progress(ProgressMessage message);
}
