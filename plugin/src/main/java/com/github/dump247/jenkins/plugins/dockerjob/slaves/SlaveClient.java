package com.github.dump247.jenkins.plugins.dockerjob.slaves;

import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.google.common.base.Charsets;
import com.google.common.net.HostAndPort;
import com.google.inject.Provider;
import com.trilead.ssh2.ChannelCondition;
import com.trilead.ssh2.Connection;
import com.trilead.ssh2.SFTPv3Client;
import com.trilead.ssh2.Session;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import static com.github.dump247.jenkins.plugins.dockerjob.slaves.Sftp.writeFile;
import static com.github.dump247.jenkins.plugins.dockerjob.slaves.Sftp.writeResource;
import static com.github.dump247.jenkins.plugins.dockerjob.slaves.Sftp.writeString;
import static com.github.dump247.jenkins.plugins.dockerjob.slaves.Ssh.communicateSuccess;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Lists.newArrayList;
import static java.lang.String.format;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.FINER;
import static org.joda.time.Duration.standardSeconds;

/**
 * Client that connects to a slave host machine and launches slave docker containers.
 */
public class SlaveClient {
    private static final Logger LOG = Logger.getLogger(SlaveClient.class.getName());

    private final SshClient _sshClient;
    private final Map<String, Set<Integer>> _activeJobRunNumbers = new HashMap<String, Set<Integer>>();

    public SlaveClient(HostAndPort host, Provider<StandardUsernameCredentials> credentialsProvider) {
        _sshClient = new SshClient(host, credentialsProvider);
    }

    public SlaveClient(HostAndPort host, Provider<StandardUsernameCredentials> credentialsProvider, int maxSessions) {
        _sshClient = new SshClient(host, credentialsProvider, maxSessions);
    }

    public HostAndPort getHost() {
        return _sshClient.getHost();
    }

    public int sessionCount() {
        return _sshClient.sessionCount();
    }

    public void ping() throws IOException {
        _sshClient.ping();
    }

    public void close() {
        _sshClient.close();
    }

    public String initialize(URL slaveJarUrl, String slaveInitScript) throws IOException {
        Connection connection = null;
        SFTPv3Client ftp = null;

        LOG.log(FINE, "Initializing {0}", getHost());

        try {
            connection = _sshClient.connect();
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
        String runName;
        int runNumber;

        synchronized (_activeJobRunNumbers) {
            Set<Integer> runNumbers = _activeJobRunNumbers.get(options.getName());

            if (runNumbers == null) {
                runNumbers = new HashSet<Integer>();
                _activeJobRunNumbers.put(options.getName(), runNumbers);
            }

            runNumber = 1;
            while (!runNumbers.add(runNumber)) {
                runNumber += 1;
            }

            runName = options.getName() + "-" + runNumber;
        }

        List<String> command = newArrayList("python3", "/var/lib/jenkins-docker/create_slave.py",
                "--name", runName,
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
        SlaveConnection connection = new SlaveConnection(_sshClient.createSession(), options.getName(), runNumber);

        try {
            connection._session.execCommand(commandString);
            return connection;
        } catch (IOException ex) {
            connection.close();
            throw ex;
        }
    }

    public class SlaveConnection {
        private final SshClient.SshSession _session;
        private final String _jobName;
        private final int _runNumber;
        private boolean _closed;

        private SlaveConnection(SshClient.SshSession session, String jobName, int runNumber) {
            _session = session;
            _jobName = jobName;
            _runNumber = runNumber;
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

        @Override
        protected void finalize() throws Throwable {
            super.finalize();
            close();
        }

        public synchronized void close() {
            if (!_closed) {
                _closed = true;

                synchronized (_activeJobRunNumbers) {
                    Set<Integer> runNumbers = _activeJobRunNumbers.get(_jobName);

                    if (runNumbers != null) {
                        runNumbers.remove(_runNumber);

                        if (runNumbers.isEmpty()) {
                            _activeJobRunNumbers.remove(_jobName);
                        }
                    }
                }

                _session.close();
            }
        }
    }
}
