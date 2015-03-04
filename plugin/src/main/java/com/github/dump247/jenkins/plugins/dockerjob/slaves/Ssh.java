package com.github.dump247.jenkins.plugins.dockerjob.slaves;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.google.common.base.Charsets;
import com.google.common.base.Throwables;
import com.google.common.io.CharStreams;
import com.google.common.net.HostAndPort;
import com.trilead.ssh2.ChannelCondition;
import com.trilead.ssh2.Connection;
import com.trilead.ssh2.Session;
import com.trilead.ssh2.StreamGobbler;
import hudson.util.Secret;
import org.joda.time.Duration;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.List;
import java.util.regex.Pattern;

import static com.google.common.base.Objects.firstNonNull;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.String.format;
import static java.util.Arrays.asList;

/**
 * Execute a command over SSH and capture the result.
 */
public class Ssh {
    private static final Pattern REQUIRES_QUOTES = Pattern.compile("[\\s\"']");

    public static Connection connect(HostAndPort host, StandardUsernameCredentials credentials) throws IOException {
        Connection connection = new Connection(host.getHostText(), host.getPortOrDefault(22));
        connection.setTCPNoDelay(true);
        connection.connect();

        try {
            if (credentials instanceof StandardUsernamePasswordCredentials) {
                StandardUsernamePasswordCredentials passwordCredentials = (StandardUsernamePasswordCredentials) credentials;
                connection.authenticateWithPassword(passwordCredentials.getUsername(), Secret.toString(passwordCredentials.getPassword()));
            } else if (credentials instanceof SSHUserPrivateKey) {
                SSHUserPrivateKey sshCredentials = (SSHUserPrivateKey) credentials;
                checkState(sshCredentials.getPrivateKeys().size() > 0, "no private keys defined");

                String username = credentials.getUsername();
                String password = Secret.toString(sshCredentials.getPassphrase());

                for (String privateKey : sshCredentials.getPrivateKeys()) {
                    if (connection.authenticateWithPublicKey(username, privateKey.toCharArray(), password)) {
                        break;
                    }
                }
            } else {
                connection.authenticateWithNone(credentials.getUsername());
            }

            checkState(connection.isAuthenticationComplete(), "Authentication failed");
        } catch (Throwable ex) {
            connection.close();
            throw Throwables.propagate(ex);
        }

        return connection;
    }

    public static CommunicateResult communicate(Connection connection, Duration timeout, String... command) throws IOException {
        checkNotNull(connection);
        checkNotNull(timeout);
        checkNotNull(command);

        String commandString = quoteCommand(asList(command));
        Session session = connection.openSession();

        try {
            session.execCommand(commandString);

            StreamGobbler stderr = new StreamGobbler(session.getStderr());
            StreamGobbler stdout = new StreamGobbler(session.getStdout());

            try {
                session.waitForCondition(ChannelCondition.EXIT_STATUS, timeout.getMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            return new CommunicateResult(
                    commandString,
                    readAll(stdout, Charsets.UTF_8),
                    readAll(stderr, Charsets.UTF_8),
                    firstNonNull(session.getExitStatus(), -1000)
            );
        } finally {
            session.close();
        }
    }

    public static String communicateSuccess(Connection connection, Duration timeout, String... command) throws IOException {
        CommunicateResult result = communicate(connection, timeout, command);

        if (result.getExitCode() != 0) {
            String message = format("Command failed: %s\nExit Code: %d\n%s", truncate(result.getCommand(), 128), result.getExitCode(), result.getStderr());
            throw new RuntimeException(message.trim());
        }

        return result.getStdout();
    }

    private static String truncate(String value, int length) {
        if (value.length() >= length) {
            value = value.substring(0, length - 3) + "...";
        }

        int lineBreak = value.indexOf('\n');

        if (lineBreak >= 0) {
            value = value.substring(0, lineBreak) + "...";
        }

        return value;
    }

    public static String quoteCommand(List<String> command) {
        StringBuilder result = new StringBuilder();

        for (String c : command) {
            if (result.length() > 0) {
                result.append(' ');
            }

            if (REQUIRES_QUOTES.matcher(c).find()) {
                result.append('"');
                result.append(c.replaceAll("\"", "\\\""));
                result.append('"');
            } else {
                result.append(c);
            }
        }

        return result.toString();
    }

    private static String readAll(InputStream stream, Charset charset) throws IOException {
        InputStreamReader reader = new InputStreamReader(stream, charset);
        return CharStreams.toString(reader);
    }

    public static class CommunicateResult {
        private final String _command;
        private final String _stdout;
        private final String _stderr;
        private final int _exitCode;

        public CommunicateResult(String command, String stdout, String stderr, int exitCode) {
            _command = command;
            _stdout = stdout;
            _stderr = stderr;
            _exitCode = exitCode;
        }

        public String getCommand() {
            return _command;
        }

        public String getStdout() {
            return _stdout;
        }

        public String getStderr() {
            return _stderr;
        }

        public int getExitCode() {
            return _exitCode;
        }
    }
}
