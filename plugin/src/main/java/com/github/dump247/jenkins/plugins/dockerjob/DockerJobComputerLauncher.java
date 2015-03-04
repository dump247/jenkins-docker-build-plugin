package com.github.dump247.jenkins.plugins.dockerjob;

import com.github.dump247.jenkins.plugins.dockerjob.slaves.SlaveClient;
import com.github.dump247.jenkins.plugins.dockerjob.slaves.SlaveOptions;
import com.google.common.base.Charsets;
import com.google.common.base.Throwables;
import com.google.common.net.HostAndPort;
import hudson.Extension;
import hudson.model.TaskListener;
import hudson.remoting.Channel;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.SlaveComputer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.logging.Logger;

import static java.util.logging.Level.FINER;
import static java.util.logging.Level.WARNING;

public class DockerJobComputerLauncher extends ComputerLauncher {
    private static final Logger LOG = Logger.getLogger(DockerJobComputerLauncher.class.getName());

    private final SlaveClient _client;
    private final SlaveOptions _options;

    public DockerJobComputerLauncher(SlaveClient client, SlaveOptions options) {
        _client = client;
        _options = options;
    }

    public HostAndPort getHost() {
        return _client.getHost();
    }

    @Override
    public void launch(SlaveComputer computer, final TaskListener listener) throws IOException, InterruptedException {
        final SlaveClient.SlaveConnection connection = _client.createSlave(_options);

        final Thread logReader = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getLog(), Charsets.UTF_8));
                    PrintStream logger = listener.getLogger();
                    String line;

                    while ((line = reader.readLine()) != null) {
                        logger.println(line);
                    }
                } catch (Throwable ex) {
                    LOG.log(WARNING, "Error reading log stream for job " + _options.getName(), ex);
                }
            }
        });
        logReader.setDaemon(true);
        logReader.setName(_options.getName().replaceAll("[^a-zA-Z0-9_-]", "_") + "-log-reader");
        logReader.start();

        try {
            computer.setChannel(connection.getOutput(), connection.getInput(), listener, new Channel.Listener() {
                @Override
                public void onClosed(Channel channel, IOException cause) {
                    try {
                        logReader.interrupt();
                        logReader.join(5000);
                    } catch (Throwable ex) {
                        LOG.log(FINER, "Error stopping log reading thread for job " + _options.getName(), ex);
                    }

                    connection.close();
                }
            });
        } catch (IOException ex) {
            connection.close();
            throw ex;
        } catch (Throwable ex) {
            connection.close();
            throw Throwables.propagate(ex);
        }
    }

    @Extension
    public static class Descriptor extends hudson.model.Descriptor<ComputerLauncher> {
        @Override
        public String getDisplayName() {
            return "Docker Computer Launcher";
        }
    }
}
