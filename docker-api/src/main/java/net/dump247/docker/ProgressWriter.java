package net.dump247.docker;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.Writer;

/** Writes progress messages to a stream. */
public class ProgressWriter implements ProgressMonitor {
    private final PrintWriter _writer;
    private final PrintWriter _errorWriter;

    public ProgressWriter(PrintStream writer) {
        this(writer, writer);
    }

    public ProgressWriter(PrintStream writer, PrintStream errorWriter) {
        if (writer == null) {
            throw new NullPointerException("writer");
        }

        if (errorWriter == null) {
            throw new NullPointerException("errorWriter");
        }

        _writer = new PrintWriter(writer, true);
        _errorWriter = errorWriter == writer
                ? _writer
                : new PrintWriter(errorWriter, true);
    }

    public ProgressWriter(Writer writer) {
        this(writer, writer);
    }

    public ProgressWriter(Writer writer, Writer errorWriter) {
        if (writer == null) {
            throw new NullPointerException("writer");
        }

        if (errorWriter == null) {
            throw new NullPointerException("errorWriter");
        }

        _writer = wrap(writer);
        _errorWriter = writer == errorWriter
                ? _writer
                : wrap(errorWriter);
    }

    @Override
    public void progress(final ProgressMessage message) {
        if (message.isError()) {
            _errorWriter.println(message);
        } else {
            _writer.println(message);
        }
    }

    private static PrintWriter wrap(Writer writer) {
        return writer instanceof PrintWriter
                ? (PrintWriter) writer
                : new PrintWriter(writer, true);
    }
}
