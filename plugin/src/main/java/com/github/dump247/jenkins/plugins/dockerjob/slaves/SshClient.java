package com.github.dump247.jenkins.plugins.dockerjob.slaves;

import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.google.common.base.Optional;
import com.google.common.net.HostAndPort;
import com.google.inject.Provider;
import com.trilead.ssh2.ChannelCondition;
import com.trilead.ssh2.Connection;
import com.trilead.ssh2.Session;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Lists.newArrayList;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.FINER;

/**
 * Manages connections and sessions to an SSH server.
 */
public class SshClient {
    public static final int DEFAULT_MAX_SESSIONS = 5;
    private static final Logger LOG = Logger.getLogger(SshClient.class.getName());

    private final HostAndPort _host;
    private final Provider<StandardUsernameCredentials> _credentialsProvider;

    /**
     * Maximum number of sessions to open on a connection before creating a new connection.
     */
    private final int _maxSessions;

    private List<SessionCount> _connections = newArrayList();

    public SshClient(HostAndPort host, Provider<StandardUsernameCredentials> credentialsProvider) {
        this(host, credentialsProvider, DEFAULT_MAX_SESSIONS);
    }

    public SshClient(HostAndPort host, Provider<StandardUsernameCredentials> credentialsProvider, int maxSessions) {
        _host = checkNotNull(host);
        _credentialsProvider = checkNotNull(credentialsProvider);
        _maxSessions = maxSessions;

        checkArgument(maxSessions > 0);
    }

    public HostAndPort getHost() {
        return _host;
    }

    /**
     * Total number of open SSH sessions.
     */
    public synchronized int sessionCount() {
        int total = 0;

        for (SessionCount connection : _connections) {
            total += connection.sessionCount;
        }

        return total;
    }

    /**
     * Test the connection to the SSH server.
     */
    public void ping() throws IOException {
        String commandString = Ssh.quoteCommand("true");
        SshSession session = createSession();

        try {
            session.execCommand(commandString);

            if (session.waitForExit(5, TimeUnit.SECONDS).or(Integer.MIN_VALUE) != 0) {
                throw new RuntimeException("Unknown error pinging host");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } finally {
            closeSession(session);
        }
    }

    public Connection connect() throws IOException {
        return Ssh.connect(_host, _credentialsProvider.get());
    }

    public Provider<StandardUsernameCredentials> getCredentialsProvider() {
        return _credentialsProvider;
    }

    public synchronized void close() {
        for (SessionCount count : _connections) {
            count.connection.close();
        }

        _connections.clear();
    }

    public synchronized SshSession createSession() throws IOException {
        SessionCount selected = null;

        // Find connection with most sessions, but still less than max. This maximizes the number
        // of sessions per connection to minimize open connections.
        for (SessionCount count : _connections) {
            if (count.sessionCount < _maxSessions) {
                if (selected == null || selected.sessionCount < count.sessionCount) {
                    selected = count;
                }
            }
        }

        if (selected == null) {
            LOG.log(FINE, "Opening connection to {0}", _host);
            selected = new SessionCount(connect());
            _connections.add(selected);
        }

        LOG.log(FINER, "Opening session to {0}", _host);
        Session session = selected.connection.openSession();
        selected.sessionCount += 1;
        return new SshSession(selected.connection, session);
    }

    private synchronized void closeSession(SshSession session) {
        int emptyConnections = 0;

        LOG.log(FINER, "Closing session to {0}", _host);
        session._session.close();

        Iterator<SessionCount> iter = _connections.iterator();
        while (iter.hasNext()) {
            SessionCount count = iter.next();

            if (count.connection == session._connection) {
                count.sessionCount -= 1;
            }

            // Close the second connection that has no active sessions and any empty connection
            // after that. Keep one connection with no sessions for additional capacity.
            if (count.sessionCount == 0) {
                emptyConnections += 1;

                if (emptyConnections > 1) {
                    LOG.log(FINE, "Closing connection to {0}", _host);
                    count.connection.close();
                    iter.remove();
                }
            }
        }
    }

    public final class SshSession {
        private final Session _session;
        private final Connection _connection;
        private boolean _closed;

        public SshSession(Connection connection, Session session) {
            _connection = connection;
            _session = session;
        }

        public InputStream getStdout() {
            return _session.getStdout();
        }

        public OutputStream getStdin() {
            return _session.getStdin();
        }

        public InputStream getStderr() {
            return _session.getStderr();
        }

        @Override
        protected void finalize() throws Throwable {
            super.finalize();
            close();
        }

        public synchronized void close() {
            if (!_closed) {
                _closed = true;
                closeSession(this);
            }
        }

        public void execCommand(String cmd) throws IOException {
            _session.execCommand(cmd);
        }

        public Optional<Integer> waitForExit(long timeout, TimeUnit timeoutUnit) throws InterruptedException {
            _session.waitForCondition(ChannelCondition.EXIT_STATUS, timeoutUnit.toMillis(timeout));
            return Optional.fromNullable(_session.getExitStatus());
        }
    }

    private static class SessionCount {
        public final Connection connection;
        public int sessionCount;

        public SessionCount(Connection connection) {
            this.connection = connection;
        }
    }
}
