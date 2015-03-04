package com.github.dump247.jenkins.plugins.dockerjob.slaves;

import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.google.common.net.HostAndPort;
import com.google.inject.Provider;
import com.trilead.ssh2.Connection;
import com.trilead.ssh2.SFTPv3Client;
import com.trilead.ssh2.Session;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static com.github.dump247.jenkins.plugins.dockerjob.slaves.Sftp.writeFile;
import static com.github.dump247.jenkins.plugins.dockerjob.slaves.Sftp.writeResource;
import static com.github.dump247.jenkins.plugins.dockerjob.slaves.Ssh.communicateSuccess;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.logging.Level.FINER;
import static org.joda.time.Duration.standardSeconds;

/**
 * Client that connects to a slave host machine and launches slave docker containers.
 */
public class SlaveClient {
    private static final Logger LOG = Logger.getLogger(SlaveClient.class.getName());

    private final HostAndPort _host;
    private final Provider<StandardUsernameCredentials> _credentialsProvider;

    private Connection _connection;

    public SlaveClient(HostAndPort host, Provider<StandardUsernameCredentials> credentialsProvider) {
        _host = checkNotNull(host);
        _credentialsProvider = checkNotNull(credentialsProvider);
    }

    public HostAndPort getHost() {
        return _host;
    }

    public Provider<StandardUsernameCredentials> getCredentialsProvider() {
        return _credentialsProvider;
    }

    public void connect() throws IOException {
        if (_connection == null) {
            _connection = Ssh.connect(_host, _credentialsProvider.get());
        }
    }

    public void close() {
        if (_connection != null) {
            _connection.close();
        }
    }

    public String initialize(URL slaveJarUrl) throws IOException {
        connect();

        SFTPv3Client ftp = new SFTPv3Client(_connection);

        try {
            writeResource(ftp, getClass(), "init_host.sh", "/var/lib/jenkins-docker/init_host.sh");

            // Run script to initialize the host (create directories, check for dependencies, etc)
            String initializeResult = communicateSuccess(
                    _connection,
                    standardSeconds(1),
                    "/bin/bash", "/var/lib/jenkins-docker/init_host.sh");

            // Upload slave files
            writeResource(ftp, getClass(), "create_slave.py", "/var/lib/jenkins-docker/create_slave.py");
            writeResource(ftp, getClass(), "launch_slave.sh", "/var/lib/jenkins-docker/slave/launch_slave.sh");
            writeFile(ftp, slaveJarUrl.openStream(), "/var/lib/jenkins-docker/slave/slave.jar");

            return initializeResult;
        } finally {
            ftp.close();
        }
    }

    public SlaveConnection createSlave(SlaveOptions options) throws IOException {
        List<String> command = asList("python3", "/var/lib/jenkins-docker/create_slave.py",
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
        return new SlaveConnection(Ssh.execute(_connection, command));
    }

    public static class SlaveConnection {
        private final Session _session;

        public SlaveConnection(Session session) {
            _session = session;
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

        public void close() {
            LOG.log(FINER, "Closing SSH session");
            _session.close();
        }
    }
}
