package net.dump247.jenkins.plugins.dockerbuild;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.LauncherDecorator;
import hudson.Proc;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Executor;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.remoting.Channel;

import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.google.common.base.Strings.isNullOrEmpty;

/** Runs commands for a Jenkins job in a Docker container. */
@Extension
public class DockerLauncherDecorator extends LauncherDecorator {
    private static final Logger LOG = Logger.getLogger(DockerLauncherDecorator.class.getName());

    private final ExecutorService _executorService = Executors.newCachedThreadPool();

    @Override
    public Launcher decorate(final Launcher launcher, final Node node) {
        Executor executor = Executor.currentExecutor();

        if (executor == null) {
            LOG.log(Level.WARNING, "Current job executor not found. Can launch command in docker container.");
            return launcher;
        }

        Queue.Executable executable = executor.getCurrentExecutable();

        if (!(executable instanceof AbstractBuild)) {
            LOG.log(Level.WARNING, "Invalid current job executable type: [expected={0}] [actual={1}]", new Object[] {AbstractBuild.class, executable == null ? "null" : executable.getClass()});
            return launcher;
        }

        AbstractBuild build = (AbstractBuild) executable;
        AbstractProject project = build.getProject();
        DockerJobProperty config = (DockerJobProperty) project.getProperty(DockerJobProperty.class);

        if (config == null || isNullOrEmpty(config.getImage())) {
            LOG.log(Level.FINE, "No docker image configured for job {0} {2}", new Object[] {project.getName(), build.getDisplayName()});
            return launcher;
        }

        return new DecoratedLauncher(launcher, config);
    }

    private static OutputStream streamIfNull(OutputStream stream, OutputStream defaultStream) {
        return stream == null ? defaultStream : stream;
    }

    public class DecoratedLauncher extends Launcher {
        private final DockerJobProperty _jobConfig;

        protected DecoratedLauncher(final Launcher launcher, final DockerJobProperty jobConfig) {
            super(launcher);
            _jobConfig = jobConfig;
        }

        @Override
        public Proc launch(final ProcStarter starter) throws IOException {
            DockerRunner dockerRunner = new DockerRunner(_jobConfig.getImage(), starter.cmds());
            dockerRunner.setWorkingDirectory(starter.pwd().getRemote());

            Map<String, String> environment = getEnvironment(starter);
            dockerRunner.setEnvironment(_jobConfig.getEnvironment(environment, System.getProperties()));
            dockerRunner.setDirectoryBindings(_jobConfig.getDirectoryBindings(environment, System.getProperties()));

            OutputStream stdout = streamIfNull(starter.stdout(), NullOutputStream.INSTANCE);
            dockerRunner.setStdout(stdout);

            OutputStream stderr = streamIfNull(starter.stderr(), stdout);
            dockerRunner.setStderr(stderr);

            return new AsyncJenkinsProc(_executorService.submit(dockerRunner));
        }

        @Override
        public Channel launchChannel(final String[] cmd, final OutputStream out, final FilePath workDir, final Map<String, String> envVars) throws IOException, InterruptedException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void kill(final Map<String, String> modelEnvVars) throws IOException, InterruptedException {
            // TODO implement this method
            // It gets called after a job runs
        }

        private Map<String, String> getEnvironment(final ProcStarter starter) throws IOException {
            Map<String, String> environment = new HashMap<String, String>();

            // Copy host environment
            for (String envVar : starter.envs()) {
                String[] parts = envVar.split("=", 2);
                environment.put(parts[0], parts[1]);
            }

            return environment;
        }
    }
}
