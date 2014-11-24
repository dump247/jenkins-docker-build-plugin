package net.dump247.docker;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/**
 * Construct {@link java.io.InputStream} and {@link java.io.OutputStream} adapters to ensure that
 * the underlying socket is only closed when both input and output are closed.
 * <p/>
 * This class has the limitation that closing a read/write operation may not become unblocked when
 * the stream is closed. Only when both streams are closed and, therefore, the underlying socket is
 * closed.
 */
public class SocketStreams {
    private final Object _syncObj = new Object();
    private final InputStream _inputStream;
    private volatile boolean _inputClosed;
    private final OutputStream _outputStream;
    private volatile boolean _outputClosed;

    private SocketStreams(InputStream socketInput, OutputStream socketOutput) {
        _inputStream = new InputStreamAdapter(socketInput);
        _outputStream = new OutputStreamAdapter(socketOutput);
    }

    public InputStream getInputStream() {
        return _inputStream;
    }

    public OutputStream getOutputStream() {
        return _outputStream;
    }

    public static SocketStreams create(Socket socket) throws IOException {
        return new SocketStreams(socket.getInputStream(), socket.getOutputStream());
    }

    private class OutputStreamAdapter extends OutputStream {
        private final OutputStream _wrapped;

        public OutputStreamAdapter(OutputStream wrapped) {
            _wrapped = wrapped;
        }

        @Override
        public void write(int b) throws IOException {
            if (_outputClosed) {
                throw new IOException("stream closed");
            }

            _wrapped.write(b);
        }

        @Override
        public void write(byte[] b) throws IOException {
            if (_outputClosed) {
                throw new IOException("stream closed");
            }

            _wrapped.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (_outputClosed) {
                throw new IOException("stream closed");
            }

            _wrapped.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            if (_outputClosed) {
                throw new IOException("stream closed");
            }

            _wrapped.flush();
        }

        @Override
        public void close() throws IOException {
            boolean closeWrapped = false;

            synchronized (_syncObj) {
                if (_outputClosed) {
                    return;
                }

                _outputClosed = true;
                closeWrapped = _inputClosed;
            }

            if (closeWrapped) {
                _wrapped.close();
            }
        }
    }

    private class InputStreamAdapter extends InputStream {
        private final InputStream _wrapped;

        public InputStreamAdapter(InputStream wrapped) {
            _wrapped = wrapped;
        }

        @Override
        public int read() throws IOException {
            if (_inputClosed) {
                throw new IOException("stream closed");
            }

            return _wrapped.read();
        }

        @Override
        public int read(byte[] b) throws IOException {
            if (_inputClosed) {
                throw new IOException("stream closed");
            }

            return _wrapped.read(b);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (_inputClosed) {
                throw new IOException("stream closed");
            }


            return _wrapped.read(b, off, len);
        }

        @Override
        public long skip(long n) throws IOException {
            if (_inputClosed) {
                throw new IOException("stream closed");
            }

            return _wrapped.skip(n);
        }

        @Override
        public int available() throws IOException {
            if (_inputClosed) {
                throw new IOException("stream closed");
            }

            return _wrapped.available();
        }

        @Override
        public void close() throws IOException {
            boolean closeWrapped = false;

            synchronized (_syncObj) {
                if (_inputClosed) {
                    return;
                }

                _inputClosed = true;
                closeWrapped = _outputClosed;
            }

            if (closeWrapped) {
                _wrapped.close();
            }
        }
    }
}
