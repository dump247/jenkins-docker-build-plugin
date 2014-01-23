package net.dump247.docker;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * !!!! Borrowed from hudson.util
 *
 * {@link Thread} that copies {@link java.io.InputStream} to {@link java.io.OutputStream}.
 *
 * @author Kohsuke Kawaguchi
 */
public class StreamCopyThread extends Thread {
    private final InputStream in;
    private final OutputStream out;
    private final boolean closeOut;

    public StreamCopyThread(String threadName, InputStream in, OutputStream out, boolean closeOut) {
        super(threadName);
        this.in = in;
        if (out == null) {
            throw new NullPointerException("out is null");
        }
        this.out = out;
        this.closeOut = closeOut;
    }

    public StreamCopyThread(String threadName, InputStream in, OutputStream out) {
        this(threadName,in,out,false);
    }

    @Override
    public void run() {
        try {
            try {
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0)
                    out.write(buf, 0, len);
            } finally {
                // it doesn't make sense not to close InputStream that's already EOF-ed,
                // so there's no 'closeIn' flag.
                in.close();
                if(closeOut)
                    out.close();
            }
        } catch (IOException e) {
            // TODO: what to do?
        }
    }
}