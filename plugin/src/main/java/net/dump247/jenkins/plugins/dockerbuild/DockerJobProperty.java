package net.dump247.jenkins.plugins.dockerbuild;

import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.Job;
import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;
import hudson.util.FormValidation;
import net.dump247.docker.DirectoryBinding;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.base.Strings.isNullOrEmpty;
import static java.lang.String.format;

/** Properties that configure the Docker container for a job. */
public class DockerJobProperty extends JobProperty<AbstractProject<?, ?>> {
    private static final Pattern VAR_PATTERN = Pattern.compile("\\$\\{(.*?)\\}");

    private final String _image;
    private final String _directoryBindings;

    /**
     * Initialize a new instance.
     *
     * @param image             name of the docker image to run the job on
     * @param directoryBindings host to container directory bindings, one per line
     */
    @DataBoundConstructor
    public DockerJobProperty(final String image, final String directoryBindings) {
        _image = image;
        _directoryBindings = directoryBindings;
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
     * Get the configured directory bindings.
     *
     * @param environment      environment variables to substitute in bindings
     * @param systemProperties system properties to substitute in bindings
     * @return directory bindings
     */
    public List<DirectoryBinding> getDirectoryBindings(Map<String, String> environment, Properties systemProperties) {
        List<DirectoryBinding> bindings = new ArrayList<DirectoryBinding>();

        for (String line : _directoryBindings.split("\n")) {
            String[] lineParts = line.split(":");
            String hostPath = lineParts[0].trim();
            String containerPath = lineParts.length > 1 ? lineParts[1].trim() : "";
            String accessStr = lineParts.length > 2 ? lineParts[2].trim() : "";

            DirectoryBinding.Access access = DirectoryBinding.Access.READ_WRITE;
            if ("r".equals(accessStr)) {
                access = DirectoryBinding.Access.READ;
            }

            if (containerPath.length() == 0) {
                containerPath = hostPath;
            }

            hostPath = replaceVars(environment, systemProperties, hostPath);
            containerPath = replaceVars(environment, systemProperties, containerPath);
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
        Map<String, String> newEnvironment = new HashMap<String, String>(environment);

        for (String line : _directoryBindings.split("\n")) {
            line = line.trim();

            if (line.startsWith("!")) {
                newEnvironment.remove(line.substring(1).trim());
            } else {
                String[] lineParts = line.split("=", 2);
                String varName = lineParts[0].trim();
                String varValue = lineParts.length > 1 ? lineParts[1].trim() : "";

                varValue = replaceVars(newEnvironment, systemProperties, varValue);
                newEnvironment.put(varName, varValue);
            }
        }

        return newEnvironment;
    }

    private static String replaceVars(Map<String, String> environment, Properties systemProperties, String value) {
        Matcher matcher = VAR_PATTERN.matcher(value);
        StringBuffer buffer = new StringBuffer();

        while (matcher.find()) {
            String[] varParts = matcher.group(1).split(":");
            String varType = varParts[0];
            String varName = varParts[1];

            if ("sys".equals(varType)) {
                appendReplacement(matcher, buffer, systemProperties.getProperty(varName));
            } else if ("env".equals(varType)) {
                appendReplacement(matcher, buffer, environment.get(varName));
            } else {
                matcher.appendReplacement(buffer, "");
            }
        }

        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static void appendReplacement(Matcher matcher, StringBuffer buffer, String replacement) {
        replacement = replacement == null ? "" : replacement;
        matcher.appendReplacement(buffer, replacement.replace("\\", "\\\\").replace("$", "\\$"));
    }

    private static void appendLine(StringBuilder buffer, String format, Object... args) {
        if (buffer.length() > 0) {
            buffer.append("\n");
        }

        buffer.append(format(format, args));
    }

    @Extension
    public static class Descriptor extends JobPropertyDescriptor {
        @Override
        public String getDisplayName() {
            return "Docker Container";
        }

        @Override
        public boolean isApplicable(final Class<? extends Job> jobType) {
            return AbstractProject.class.isAssignableFrom(jobType);
        }

        public String defaultDirectoryBindings() {
            return "${sys:java.io.tmpdir}";
        }

        public String defaultEnvironment() {
            return "!-\n" +
                    "!CLASSPATH\n" +
                    "!HOME\n" +
                    "!HUDSON_HOME\n" +
                    "!JENKINS_HOME\n" +
                    "!LD_LIBRARY_PATH\n" +
                    "!MAIL\n" +
                    "!PATH\n" +
                    "!PWD\n" +
                    "!SHELL\n" +
                    "!SHLVL\n" +
                    "!TERM\n" +
                    "!USER";
        }

        public FormValidation doCheckEnvironment(@QueryParameter String value) {
            if (isNullOrEmpty(value)) {
                return FormValidation.ok();
            }

            int lineNum = 0;
            StringBuilder error = new StringBuilder();

            for (String line : value.split("\n")) {
                lineNum += 1;
                line = line.trim();

                if (line.length() == 0) {
                    continue;
                }

                if (line.startsWith("!")) {
                    String varName = line.substring(1).trim();

                    if (varName.length() == 0) {
                        appendLine(error, "Line %d: Missing variable name", lineNum);
                        continue;
                    }
                } else {
                    String[] lineParts = line.split("=");

                    if (lineParts.length != 2) {
                        appendLine(error, "Line %d: Invalid variable assignment %s", lineNum, line);
                        continue;
                    }

                    String varName = lineParts[0].trim();
                    String varValue = lineParts[1].trim();

                    if (varName.length() == 0) {
                        appendLine(error, "Line %d: Missing variable name in %s", lineNum, line);
                        continue;
                    }

                    if (!checkVariables(lineNum, varValue, error)) {
                        continue;
                    }
                }
            }

            return error.length() > 0
                    ? FormValidation.error(error.toString())
                    : FormValidation.ok();
        }

        public FormValidation doCheckDirectoryBindings(@QueryParameter String value) {
            if (isNullOrEmpty(value)) {
                return FormValidation.ok();
            }

            int lineNum = 0;
            StringBuilder error = new StringBuilder();

            for (String line : value.split("\n")) {
                lineNum += 1;
                line = line.trim();

                if (line.length() == 0) {
                    continue;
                }

                String[] lineParts = line.split(":");

                if (lineParts.length > 3) {
                    appendLine(error, "Line %d: Too many colons in %s", lineNum, line);
                    continue;
                }

                String hostPath = lineParts[0].trim();
                String containerPath = lineParts.length > 1 ? lineParts[1].trim() : "";
                String access = lineParts.length > 2 ? lineParts[2].trim() : "";

                if (hostPath.length() == 0) {
                    appendLine(error, "Line %d: Missing host path in %s", lineNum, line);
                    continue;
                } else if (!checkVariables(lineNum, hostPath, error)) {
                    continue;
                }

                if (!checkVariables(lineNum, containerPath, error)) {
                    continue;
                }

                if (!"".equals(access) && !"r".equals(access) && !"rw".equals(access)) {
                    appendLine(error, "Line %d: Invalid access value in %s", lineNum, line);
                    continue;
                }
            }

            return error.length() > 0
                    ? FormValidation.error(error.toString())
                    : FormValidation.ok();
        }

        private boolean checkVariables(int lineNum, String value, StringBuilder error) {
            Matcher matcher = VAR_PATTERN.matcher(value);

            while (matcher.find()) {
                String content = matcher.group(1);

                if (!content.startsWith("env:") || !content.startsWith("sys:")) {
                    appendLine(error, "Line %d: Invalid variable reference %s", lineNum, value);
                    return false;
                }

                String varName = content.substring(0, content.indexOf(":")).trim();

                if (varName.length() == 0) {
                    appendLine(error, "Line %d: Missing variable name in %s", lineNum, value);
                    return false;
                }
            }

            return true;
        }
    }
}
