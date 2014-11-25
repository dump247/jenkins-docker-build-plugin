package net.dump247.docker;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Demultiplexes a docker data input stream.
 * <p/>
 * Creates two streams that receive stdout and stderr, respectively. Once both demultiplexed streams
 * are closed, the underlying input stream is closed.
 */
public class StreamDemultiplexer {
    private static final int STDOUT_STREAM = 1;
    private static final int STDERR_STREAM = 2;

    private final Object _syncObject = new Object();
    private final InputStream _wrapped;
    private final InputStream _stderr;
    private final InputStream _stdout;

    private final byte[] _headerBuffer = new byte[8];
    private int _messageStreamId;
    private long _messageLength;

    private int _readStreamId;
    private int _closedStreams;

    public StreamDemultiplexer(InputStream wrapped) {
        _wrapped = wrapped;
        _stderr = new DemultiplexedStream(STDERR_STREAM);
        _stdout = new DemultiplexedStream(STDOUT_STREAM);
    }

    public InputStream getStdout() {
        return _stdout;
    }

    public InputStream getStderr() {
        return _stderr;
    }

    private void closeStream(int streamId) throws IOException {
        boolean closeWrapped = false;

        synchronized (_syncObject) {
            if (isClosed(streamId)) {
                return;
            }

            _closedStreams |= streamId;
            closeWrapped = _closedStreams == (STDERR_STREAM | STDOUT_STREAM);
            _syncObject.notifyAll();
        }

        if (closeWrapped) {
            _wrapped.close();
        }
    }

    private int readByte(int streamId) throws IOException {
        try {
            if (initStreamData(streamId)) {
                int value = _wrapped.read();
                decrementRead(value >= 0 ? 1 : -1);
                return value;
            } else {
                return -1;
            }
        } catch (IOException ex) {
            shutdownStreams();
            throw ex;
        }
    }

    private int readBytes(int streamId, byte[] buf, int off, int len) throws IOException {
        try {
            if (initStreamData(streamId)) {
                return decrementRead(_wrapped.read(buf, off, (int) Math.min(_messageLength, len)));
            } else {
                return -1;
            }
        } catch (IOException ex) {
            shutdownStreams();
            throw ex;
        }
    }

    private boolean initStreamData(int streamId) throws IOException {
        boolean success = false;

        do {
            synchronized (_syncObject) {
                while (true) {
                    if (isClosed(streamId)) {
                        return false;
                    }

                    if (_readStreamId == 0 || _readStreamId == streamId) {
                        _readStreamId = streamId;
                        break;
                    } else {
                        try {
                            _syncObject.wait();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }

            success = true;

            while (_messageLength == 0) {
                if (!readNextHeader()) {
                    shutdownStreams();
                    return false;
                }

                if (_messageStreamId != streamId) {
                    synchronized (_syncObject) {
                        if (!isClosed(_messageStreamId)) {
                            _readStreamId = _messageStreamId;
                            _syncObject.notifyAll();
                            success = false;
                            break;
                        }
                    }

                    if (_wrapped.skip(_messageLength) != _messageLength) {
                        shutdownStreams();
                        return false;
                    }

                    _messageLength = 0;
                }
            }
        } while (!success);

        return success;
    }

    private boolean readNextHeader() throws IOException {
        // read 8 bytes header
        // header is [TYPE, 0, 0, 0, SIZE, SIZE, SIZE, SIZE]
        // TYPE is 1:stdout, 2:stderr
        // SIZE is 4-byte, unsigned, big endian length of message payload

        _messageLength = 0;
        _messageStreamId = 0;

        int count = 0;

        while (count < 8) {
            int result = _wrapped.read(_headerBuffer, count, 8 - count);

            if (result < 0) {
                return false;
            }

            count += result;
        }

        if (_headerBuffer[1] != 0 || _headerBuffer[2] != 0 || _headerBuffer[3] != 0) {
            throw new IOException("Unexpected stream header content.");
        }

        _messageStreamId = _headerBuffer[0];

        if (_messageStreamId != STDERR_STREAM && _messageStreamId != STDOUT_STREAM) {
            throw new IOException("Unexpected stream id");
        }

        // Clear the type because ByteBuffer needs 8 bytes to read the long
        _headerBuffer[0] = 0;
        ByteBuffer buffer = ByteBuffer.wrap(_headerBuffer);
        buffer.order(ByteOrder.BIG_ENDIAN);
        _messageLength = buffer.getLong();

        return true;
    }

    private void shutdownStreams() throws IOException {
        boolean closeWrapped = false;

        synchronized (_syncObject) {
            closeWrapped = _closedStreams != (STDERR_STREAM | STDOUT_STREAM);
            _closedStreams = STDERR_STREAM | STDOUT_STREAM;
            _syncObject.notifyAll();
        }

        if (closeWrapped) {
            _wrapped.close();
        }
    }

    private int decrementRead(int count) throws IOException {
        if (count < 0) {
            shutdownStreams();
        } else {
            _messageLength -= count;

            if (_messageLength == 0) {
                synchronized (_syncObject) {
                    _readStreamId = 0;
                    _syncObject.notifyAll();
                }
            }
        }

        return count;
    }

    private boolean isClosed(int streamId) {
        return (_closedStreams & streamId) != 0;
    }

    private class DemultiplexedStream extends InputStream {
        private final int _streamId;

        public DemultiplexedStream(int streamId) {
            _streamId = streamId;
        }

        @Override
        public int read() throws IOException {
            return readByte(_streamId);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return readBytes(_streamId, b, off, len);
        }

        @Override
        public void close() throws IOException {
            closeStream(_streamId);
        }
    }
}
