package net.dump247.jenkins.plugins.dockerbuild;

import hudson.Extension;
import hudson.model.TaskListener;
import hudson.remoting.Channel;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.SlaveComputer;
import hudson.util.StreamCopyThread;

import java.io.IOException;
import java.io.PrintStream;

import static com.google.common.base.Preconditions.checkNotNull;

public class DockerComputerLauncher extends ComputerLauncher {
    private final DockerJob _dockerJob;

    public DockerComputerLauncher(DockerJob dockerJob) {
        _dockerJob = checkNotNull(dockerJob);
    }

    public DockerJob getDockerJob() {
        return _dockerJob;
    }

    @Override
    public void launch(final SlaveComputer computer, final TaskListener listener) throws IOException, InterruptedException {
        final PrintStream logger = listener.getLogger();
        final DockerJobContainer container = _dockerJob.start(new DockerJob.Listener() {
            @Override
            public void info(String format, Object... args) {
                logger.format(format + "\n", args);
            }

            @Override
            public void warn(String format, Object... args) {
                logger.format("WARN " + format + "\n", args);
            }

            @Override
            public void error(String format, Object... args) {
                listener.error(format + "\n", args);
            }
        });


        final StreamCopyThread stderrThread = new StreamCopyThread(container.getContainerId() + " stderr", container.getStderr(), listener.getLogger());
        stderrThread.start();

        computer.setChannel(container.getStdout(), container.getStdin(), listener, new Channel.Listener() {
            @Override
            public void onClosed(final Channel channel, final IOException cause) {
                container.stop();

                try {
                    if (stderrThread.isAlive()) {
                        stderrThread.interrupt();
                        stderrThread.join(1000);
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        });
    }

    @Extension
    public static class Descriptor extends hudson.model.Descriptor<ComputerLauncher> {
        @Override
        public String getDisplayName() {
            return "Docker Computer Launcher";
        }
    }
}
