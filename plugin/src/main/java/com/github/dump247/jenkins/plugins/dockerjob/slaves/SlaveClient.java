package com.github.dump247.jenkins.plugins.dockerjob.slaves;

import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.google.common.base.Charsets;
import com.google.common.net.HostAndPort;
import com.google.inject.Provider;
import com.trilead.ssh2.Connection;
import com.trilead.ssh2.SFTPv3Client;
import com.trilead.ssh2.Session;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static com.github.dump247.jenkins.plugins.dockerjob.slaves.Sftp.writeFile;
import static com.github.dump247.jenkins.plugins.dockerjob.slaves.Sftp.writeResource;
import static com.github.dump247.jenkins.plugins.dockerjob.slaves.Sftp.writeString;
import static com.github.dump247.jenkins.plugins.dockerjob.slaves.Ssh.communicateSuccess;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.collect.Lists.newArrayList;
import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.FINER;
import static org.joda.time.Duration.standardSeconds;

/**
 * Client that connects to a slave host machine and launches slave docker containers.
 */
public class SlaveClient {
    private static final int DEFAULT_MAX_SESSIONS = 5;
    private static final Logger LOG = Logger.getLogger(SlaveClient.class.getName());

    private final HostAndPort _host;
    private final Provider<StandardUsernameCredentials> _credentialsProvider;

    /**
     * Maximum number of sessions to open on a connection before creating a new connection.
     */
    private final int _maxSessions;

    private List<SessionCount> _connections = newArrayList();

    public SlaveClient(HostAndPort host, Provider<StandardUsernameCredentials> credentialsProvider) {
        this(host, credentialsProvider, DEFAULT_MAX_SESSIONS);
    }

    public SlaveClient(HostAndPort host, Provider<StandardUsernameCredentials> credentialsProvider, int maxSessions) {
        _host = checkNotNull(host);
        _credentialsProvider = checkNotNull(credentialsProvider);
        _maxSessions = maxSessions;

        checkArgument(maxSessions > 0);
    }

    public HostAndPort getHost() {
        return _host;
    }

    public Provider<StandardUsernameCredentials> getCredentialsProvider() {
        return _credentialsProvider;
    }

    public void close() {
        for (SessionCount count : _connections) {
            count.connection.close();
        }

        _connections.clear();
    }

    public String initialize(URL slaveJarUrl, String slaveInitScript) throws IOException {
        Connection connection = null;
        SFTPv3Client ftp = null;

        LOG.log(FINE, "Initializing {0}", _host);

        try {
            connection = Ssh.connect(_host, _credentialsProvider.get());
            ftp = new SFTPv3Client(connection);

            writeResource(ftp, getClass(), "init_host.sh", "/var/lib/jenkins-docker/init_host.sh");

            // Run script to initialize the host (create directories, check for dependencies, etc)
            String initializeResult = communicateSuccess(
                    connection,
                    standardSeconds(1),
                    "/bin/bash", "/var/lib/jenkins-docker/init_host.sh");

            // Upload slave files
            writeResource(ftp, getClass(), "create_slave.py", "/var/lib/jenkins-docker/create_slave.py");
            writeResource(ftp, getClass(), "launch_slave.sh", "/var/lib/jenkins-docker/slave/launch_slave.sh");
            writeFile(ftp, slaveJarUrl.openStream(), "/var/lib/jenkins-docker/slave/slave.jar");

            if (slaveInitScript.trim().length() > 0) {
                if (slaveInitScript.charAt(slaveInitScript.length() - 1) != '\n') {
                    slaveInitScript = slaveInitScript + "\n";
                }

                writeString(ftp, slaveInitScript, Charsets.UTF_8, "/var/lib/jenkins-docker/slave/init_slave.sh");
            }

            return initializeResult;
        } finally {
            if (ftp != null) {
                ftp.close();
            }

            if (connection != null) {
                connection.close();
            }
        }
    }

    public SlaveConnection createSlave(SlaveOptions options) throws IOException {
        List<String> command = newArrayList("python3", "/var/lib/jenkins-docker/create_slave.py",
                "--name", options.getName(),
                "--image", options.getImage());

        if (options.isCleanEnvironment()) {
            command.add("--clean");
        }

        for (Map.Entry<String, String> env : options.getEnvironment().entrySet()) {
            command.add("-e");
            command.add(format("%s=%s", env.getKey(), env.getValue()));
        }

        for (DirectoryMapping dir : options.getDirectoryMappings()) {
            command.add("-v");
            command.add(format("%s:%s:%s", dir.getHostPath(), dir.getContainerPath(), dir.getAccess().value()));
        }

        LOG.log(FINER, "Running: {0}", command);
        String commandString = Ssh.quoteCommand(command);
        SlaveConnection session = openSession();

        try {
            session._session.execCommand(commandString);
            return session;
        } catch (IOException ex) {
            closeSession(session);
            throw ex;
        }
    }

    private synchronized SlaveConnection openSession() throws IOException {
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
            selected = new SessionCount(Ssh.connect(_host, _credentialsProvider.get()));
            _connections.add(selected);
        }

        LOG.log(FINER, "Opening session to {0}", _host);
        Session session = selected.connection.openSession();
        selected.sessionCount += 1;
        return new SlaveConnection(selected.connection, session);
    }

    private synchronized void closeSession(SlaveConnection session) {
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

    public class SlaveConnection {
        private final Session _session;
        private final Connection _connection;
        private boolean _closed;

        public SlaveConnection(Connection connection, Session session) {
            _connection = connection;
            _session = session;
        }

        @Override
        protected void finalize() throws Throwable {
            super.finalize();
            close();
        }

        public InputStream getOutput() {
            return _session.getStdout();
        }

        public OutputStream getInput() {
            return _session.getStdin();
        }

        public InputStream getLog() {
            return _session.getStderr();
        }

        public synchronized void close() {
            if (!_closed) {
                _closed = true;
                closeSession(this);
            }
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
