package net.dump247.jenkins.plugins.dockerbuild;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Proc;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Run;
import hudson.remoting.Channel;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;
import hudson.util.FormValidation;
import net.dump247.docker.DirectoryBinding;
import net.dump247.docker.DockerClient;
import net.dump247.docker.ProgressEvent;
import net.dump247.docker.ProgressListener;
import org.apache.commons.lang.mutable.MutableInt;
import org.apache.commons.lang.text.StrSubstitutor;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Strings.isNullOrEmpty;
import static java.lang.String.format;

/** Pulls the associated docker image before the build starts. */
public class DockerLauncherBuildWrapper extends BuildWrapper {
    private static final Logger LOG = Logger.getLogger(DockerLauncherBuildWrapper.class.getName());

    private static final Pattern INVALID_IMAGE_CHARS = Pattern.compile("\\s");
    private static final Pattern VAR_REF_TEST_PATTERN = Pattern.compile("\\$.*?(?:\\}|$)");
    private static final String UNSET_MARKER = "unset ";
    private static final ExecutorService _executorService = Executors.newCachedThreadPool();

    private final String _image;
    private final String _directoryBindings;
    private final String _environment;

    @DataBoundConstructor
    public DockerLauncherBuildWrapper(final String image, final String directoryBindings, final String environment) {
        _image = image;
        _directoryBindings = directoryBindings;
        _environment = environment;
    }

    @Override
    public Launcher decorateLauncher(final AbstractBuild build, final Launcher launcher, final BuildListener listener) throws IOException, InterruptedException, Run.RunnerAbortedException {
        AbstractProject project = build.getProject();

        if (isNullOrEmpty(getImage())) {
            LOG.log(Level.FINE, "No docker image configured for job {0} {2}", new Object[] {project.getName(), build.getDisplayName()});
            return launcher;
        }

        return new DecoratedLauncher(launcher);
    }

    @Override
    public Environment setUp(final AbstractBuild build, final Launcher launcher, final BuildListener listener) throws IOException, InterruptedException {
        if (!isNullOrEmpty(getImage())) {
            DockerClient dockerClient = DockerClient.localClient();
            final PrintStream logger = listener.getLogger();
            final MutableInt counter = new MutableInt();
            final MutableInt progressCounter = new MutableInt();

            dockerClient.pullImage(getImage(), new ProgressListener() {
                public void progress(final ProgressEvent event) {
                    // Only print out progress or error messages
                    if (event.getCode() == ProgressEvent.Code.Ok && event.getTotal() == 0) {
                        return;
                    }

                    if (counter.intValue() == 0) {
                        logger.println(format("### Pulling Docker image %s", getImage()));
                    }

                    counter.increment();

                    String statusMessage = event.getStatusMessage();
                    String detailMessage = event.getDetailMessage();

                    StringBuilder message = new StringBuilder(statusMessage.length() + 2 + detailMessage.length());
                    message.append(statusMessage);

                    if (detailMessage.length() > 0) {
                        message.append(": ").append(detailMessage);
                    }

                    if (event.getCode() == ProgressEvent.Code.Ok) {
                        // Only output every 5 progress messages or the final progress message
                        // Otherwise, the number of messages is a little high.
                        if (progressCounter.intValue() % 5 == 0 || event.getCurrent() == event.getTotal()) {
                            logger.println(message);
                        }

                        progressCounter.increment();
                    } else {
                        listener.error(message.toString());
                    }
                }
            });

            if (counter.intValue() > 0) {
                logger.println(format("### Done pulling Docker image %s", getImage()));
            }

            logger.println(format("### Running job with Docker image %s", getImage()));
        }

        return new Environment() {
        };
    }

    /**
     * Docker image to run the job on.
     *
     * @return docker image name
     */
    public String getImage() {
        return _image;
    }

    /**
     * Get directory bindings definition.
     *
     * @return directory bindings descriptor string
     */
    public String getDirectoryBindings() {
        return _directoryBindings;
    }

    /**
     * Get environment definition.
     *
     * @return environment descriptor string.
     */
    public String getEnvironment() {
        return _environment;
    }

    /**
     * Get the configured directory bindings.
     *
     * @param environment      environment variables to substitute in bindings
     * @param systemProperties system properties to substitute in bindings
     * @return directory bindings
     */
    public List<DirectoryBinding> getDirectoryBindings(Map<String, String> environment, Properties systemProperties) {
        checkNotNull(environment);
        checkNotNull(systemProperties);

        List<DirectoryBinding> bindings = new ArrayList<DirectoryBinding>();
        StrSubstitutor substitutor = createSubstitutor(environment, systemProperties);

        for (String line : _directoryBindings.split("\n")) {
            line = line.trim();

            // Ignore empty lines and comments
            if (line.length() == 0 || line.startsWith("#")) {
                continue;
            }

            String[] lineParts = line.split(":", 3);
            String hostPath = lineParts[0].trim();
            String containerPath = get(lineParts, 1, "").trim();
            String accessStr = get(lineParts, 2, "").trim();

            DirectoryBinding.Access access = DirectoryBinding.Access.READ_WRITE;
            if ("r".equals(accessStr)) {
                access = DirectoryBinding.Access.READ;
            }

            if (containerPath.length() == 0) {
                containerPath = hostPath;
            }

            hostPath = substitutor.replace(hostPath);
            containerPath = substitutor.replace(containerPath);
            bindings.add(new DirectoryBinding(hostPath, containerPath, access));
        }

        return bindings;
    }

    /**
     * Get the configured environment variables.
     *
     * @param environment      environment variables to substitute in the configuration
     * @param systemProperties system properties to substitute in the configuration
     * @return map from variable name to value
     */
    public Map<String, String> getEnvironment(Map<String, String> environment, Properties systemProperties) {
        checkNotNull(environment);
        checkNotNull(systemProperties);

        StrSubstitutor substitutor = createSubstitutor(environment, systemProperties);
        Map<String, String> newEnvironment = new HashMap<String, String>(environment);

        for (String line : _environment.split("\n")) {
            line = line.trim();

            // Ignore empty lines and comments
            if (line.length() == 0 || line.startsWith("#")) {
                continue;
            }

            if (line.startsWith(UNSET_MARKER)) {
                newEnvironment.remove(line.substring(UNSET_MARKER.length()).trim());
            } else {
                String[] lineParts = line.split("=", 2);
                String varName = lineParts[0].trim();
                String varValue = substitutor.replace(lineParts[1].trim());
                newEnvironment.put(varName, varValue);
            }
        }

        return newEnvironment;
    }

    private static StrSubstitutor createSubstitutor(Map<String, String> environment, Properties systemProperties) {
        return new StrSubstitutor(new JobConfigStrLookup(environment, systemProperties));
    }

    private static String get(String[] array, int index, String defaultValue) {
        return index < array.length ? array[index] : defaultValue;
    }

    private static OutputStream streamIfNull(OutputStream stream, OutputStream defaultStream) {
        return stream == null ? defaultStream : stream;
    }

    @Extension
    public static class Descriptor extends BuildWrapperDescriptor {
        @Override
        public boolean isApplicable(final AbstractProject<?, ?> item) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "Docker Container";
        }

        public String defaultDirectoryBindings() {
            return "" +
                    "# Bind agent temp dir because jenkins generates build step scripts here\n" +
                    "${sys.java.io.tmpdir}\n" +
                    "\n" +
                    "# Job workspace directory\n" +
                    "${env.WORKSPACE}";
        }

        public String defaultEnvironment() {
            return "" +
                    "unset -\n" +
                    "unset CLASSPATH\n" +
                    "unset HOME\n" +
                    "unset HUDSON_HOME\n" +
                    "unset JENKINS_HOME\n" +
                    "unset LD_LIBRARY_PATH\n" +
                    "unset MAIL\n" +
                    "unset PATH\n" +
                    "unset PWD\n" +
                    "unset SHELL\n" +
                    "unset SHLVL\n" +
                    "unset TERM\n" +
                    "unset USER";
        }

        public FormValidation doCheckImage(@QueryParameter String value) {
            if (isNullOrEmpty(value)) {
                return FormValidation.error("Field is required");
            }

            if (INVALID_IMAGE_CHARS.matcher(value).find()) {
                return FormValidation.error("Field contains invalid characters");
            }

            return FormValidation.ok();
        }

        public FormValidation doCheckEnvironment(@QueryParameter String value) {
            if (isNullOrEmpty(value)) {
                return FormValidation.ok();
            }

            int lineNum = 0;

            for (String line : value.split("\n")) {
                lineNum += 1;
                line = line.trim();

                // Ignore blank lines and comments
                if (line.length() == 0 || line.startsWith("#")) {
                    continue;
                }

                if (line.startsWith(UNSET_MARKER)) {
                    String varName = line.substring(UNSET_MARKER.length()).trim();

                    if (varName.length() == 0) {
                        return error(lineNum, "Missing variable name");
                    }
                } else {
                    String[] lineParts = line.split("=");

                    if (lineParts.length != 2) {
                        return error(lineNum, "Invalid variable assignment in %s", line);
                    }

                    String varName = lineParts[0].trim();
                    String varValue = lineParts[1].trim();

                    if (varName.length() == 0) {
                        return error(lineNum, "Missing left hand side of assignment in %s", line);
                    }

                    if (!validateVariables(varValue)) {
                        return error(lineNum, "Invalid variable reference on right hand side in %s", line);
                    }
                }
            }

            return FormValidation.ok();
        }

        public FormValidation doCheckDirectoryBindings(@QueryParameter String value) {
            if (isNullOrEmpty(value)) {
                return FormValidation.ok();
            }

            int lineNum = 0;

            for (String line : value.split("\n")) {
                lineNum += 1;
                line = line.trim();

                // Ignore empty lines and comments
                if (line.length() == 0 || line.startsWith("#")) {
                    continue;
                }

                String[] lineParts = line.split(":");

                if (lineParts.length > 3) {
                    return error(lineNum, "Too many colons in %s", line);
                }

                String hostPath = lineParts[0].trim();
                String containerPath = get(lineParts, 1, "").trim();
                String access = get(lineParts, 2, "").trim();

                if (hostPath.length() == 0) {
                    return error(lineNum, "Missing host path in %s", line);
                } else if (!validateVariables(hostPath)) {
                    return error(lineNum, "Invalid variable reference in host path of %s", line);
                }

                if (!validateVariables(containerPath)) {
                    return error(lineNum, "Invalid variable reference in container path of %s", line);
                }

                if (!"".equals(access) && !"r".equals(access) && !"rw".equals(access)) {
                    return error(lineNum, "Invalid access value in %s", line);
                }
            }

            return FormValidation.ok();
        }

        private boolean validateVariables(String value) {
            Matcher matcher = VAR_REF_TEST_PATTERN.matcher(value);

            while (matcher.find()) {
                String varRef = matcher.group();

                // Must be at least: ${A}
                if (varRef.length() < 4) {
                    return false;
                }

                // Start and end with curly braces
                if (varRef.charAt(1) != '{' || varRef.charAt(varRef.length() - 1) != '}') {
                    return false;
                }

                String varName = varRef.substring(2, varRef.length() - 2).trim();

                if (varName.length() == 0) {
                    return false;
                }
            }

            return true;
        }

        private FormValidation error(int line, String format, Object... args) {
            return FormValidation.error("Line " + line + ": " + format(format, args));
        }
    }

    public class DecoratedLauncher extends Launcher {
        protected DecoratedLauncher(final Launcher launcher) {
            super(launcher);
        }

        @Override
        public Proc launch(final ProcStarter starter) throws IOException {
            DockerRunner dockerRunner = new DockerRunner(getImage(), starter.cmds());
            dockerRunner.setWorkingDirectory(starter.pwd().getRemote());

            Map<String, String> environment = buildEnvironment(starter);
            dockerRunner.setEnvironment(getEnvironment(environment, System.getProperties()));
            dockerRunner.setDirectoryBindings(getDirectoryBindings(environment, System.getProperties()));

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

        private Map<String, String> buildEnvironment(final ProcStarter starter) throws IOException {
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
