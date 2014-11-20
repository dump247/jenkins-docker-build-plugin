package net.dump247.jenkins.plugins.dockerbuild;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCertificateCredentials;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.SchemeRequirement;
import com.google.common.base.Charsets;
import com.google.common.base.Predicate;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.io.CharStreams;
import com.google.common.io.InputSupplier;
import hudson.model.AbstractProject;
import hudson.model.Computer;
import hudson.model.Item;
import hudson.model.Label;
import hudson.model.Queue;
import hudson.model.labels.LabelAtom;
import hudson.model.queue.MappingWorksheet;
import hudson.security.ACL;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import net.dump247.docker.DirectoryBinding;
import net.dump247.docker.DockerClient;
import net.dump247.docker.ImageName;
import org.apache.commons.lang.RandomStringUtils;
import org.kohsuke.stapler.QueryParameter;

import javax.annotation.Nullable;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.google.common.base.Strings.emptyToNull;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.base.Strings.nullToEmpty;
import static com.google.common.collect.Lists.newArrayListWithCapacity;
import static com.google.common.collect.Sets.newHashSet;
import static java.lang.String.format;
import static java.util.Collections.unmodifiableSet;

/**
 * Cloud of docker servers to run jenkins jobs on.
 * <p/>
 * This cloud implementation differs from the normal implementation in that it does not directly
 * provision jenkins nodes. To ensure that a specific job gets mapped to a specific slave, the
 * actual provisioning is done in {@link DockerLoadBalancer}. This cloud implementation serves
 * several important purposes:
 * <ul>
 * <li>Ensure the "Restrict where this project can be run" option is visible in job configuration</li>
 * <li>Validate that the value in "Restrict..." is valid</li>
 * <li>Makes the plugin fit into the standard jenkins configuration section (under Cloud in system settings)</li>
 * </ul>
 */
public abstract class DockerCloud extends Cloud {
    /**
     * Bash script that launches the Jenkins agent jar
     */
    private static final String AGENT_LAUNCH_SCRIPT;

    static {
        try {
            AGENT_LAUNCH_SCRIPT = CharStreams.toString(new InputSupplier<InputStreamReader>() {
                @Override
                public InputStreamReader getInput() throws IOException {
                    return new InputStreamReader(DockerComputerLauncher.class.getResourceAsStream("agent_launch.sh"), Charsets.US_ASCII);
                }
            });
        } catch (IOException ex) {
            throw Throwables.propagate(ex);
        }
    }

    private static final String IMAGE_LABEL_PREFIX = "docker/";
    private static final Logger LOG = Logger.getLogger(DockerCloud.class.getName());
    private static final SchemeRequirement HTTPS_SCHEME = new SchemeRequirement("https");
    private static final Map<String, DirectoryBinding.Access> BINDING_ACCESS = ImmutableMap.of(
            "r", DirectoryBinding.Access.READ,
            "rw", DirectoryBinding.Access.READ_WRITE
    );

    public final int dockerPort;

    public final String labelString;
    private transient Set<LabelAtom> _labels;

    public final int maxExecutors;

    public final boolean tlsEnabled;
    public final String credentialsId;

    public final String directoryMappingsString;
    private transient List<DirectoryBinding> _directoryMappings;

    public final boolean allowCustomImages;

    public final String slaveJarPath;

    protected DockerCloud(String name, final int dockerPort, final String labelString,
                          final int maxExecutors, final boolean tlsEnabled, final String credentialsId,
                          final String directoryMappingsString, boolean allowCustomImages, String slaveJarPath) {
        super(name);
        this.dockerPort = dockerPort;
        this.labelString = labelString;
        this.maxExecutors = maxExecutors;
        this.tlsEnabled = tlsEnabled;
        this.credentialsId = credentialsId;
        this.directoryMappingsString = directoryMappingsString;
        this.allowCustomImages = allowCustomImages;
        this.slaveJarPath = emptyToNull(nullToEmpty(slaveJarPath).trim());
    }

    public abstract Collection<DockerCloudHost> listHosts();

    public Set<LabelAtom> getLabels() {
        return _labels;
    }

    public List<DirectoryBinding> getDirectoryMappings() {
        return _directoryMappings;
    }

    public List<String> getLaunchCommand() {
        String slaveJarUrl = Jenkins.getInstance().getRootUrl() + "/jnlpJars/slave.jar";
        return ImmutableList.of("/bin/bash", "-l",
                "-c", format(AGENT_LAUNCH_SCRIPT, nullToEmpty(slaveJarPath), slaveJarUrl));
    }

    @Override
    public Collection<NodeProvisioner.PlannedNode> provision(Label label, int excessWorkload) {
        return ImmutableList.of();
    }

    @Override
    public boolean canProvision(Label label) {
        if (label == null) {
            return false;
        }

        // Discover image names specified in the job restriction label (i.e. docker/IMAGE)
        if (allowCustomImages) {
            for (LabelAtom potentialImage : listPotentialImages(label)) {
                Set<LabelAtom> cloudImageLabels = ImmutableSet.<LabelAtom>builder()
                        .add(potentialImage)
                        .addAll(getLabels())
                        .build();

                if (label.matches(cloudImageLabels)) {
                    return true;
                }
            }
        }

        DockerGlobalConfiguration dockerConfig = DockerGlobalConfiguration.get();

        // Discover if the job matches a pre-configured image
        for (LabeledDockerImage image : dockerConfig.getLabeledImages()) {
            Set<LabelAtom> cloudImageLabels = ImmutableSet.<LabelAtom>builder()
                    .addAll(image.getLabels())
                    .addAll(getLabels())
                    .build();

            if (image.concatCondition(label).matches(cloudImageLabels)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Initialize transient fields after deserialization.
     */
    protected Object readResolve() {
        _labels = unmodifiableSet(Label.parse(this.labelString));
        _directoryMappings = parseBindings(directoryMappingsString);
        return this;
    }

    public ProvisionResult provisionJob(DockerGlobalConfiguration configuration, Queue.Task task, MappingWorksheet.WorkChunk job) {
        if (job.assignedLabel == null) {
            return ProvisionResult.notSupported();
        }

        String jobName = job.index == 0
                ? task.getFullDisplayName()
                : format("%s_%d", task.getFullDisplayName(), job.index);

        // Check if creating a job image is enabled
        boolean resetJob = false;

        if (task instanceof AbstractProject) {
            DockerJobProperty jobProperty = (DockerJobProperty) ((AbstractProject) task).getProperty(DockerJobProperty.class);

            if (jobProperty != null) {
                resetJob = jobProperty.resetJobEnabled();
            }
        }

        // Discover image names specified in the job restriction label (i.e. docker/IMAGE)
        if (allowCustomImages) {
            for (LabelAtom potentialImage : listPotentialImages(job.assignedLabel)) {
                Set<LabelAtom> cloudImageLabels = ImmutableSet.<LabelAtom>builder()
                        .add(potentialImage)
                        .addAll(getLabels())
                        .build();

                if (job.assignedLabel.matches(cloudImageLabels)) {
                    return provisionJob(extractImageName(potentialImage), cloudImageLabels, jobName, resetJob);
                }
            }
        }

        // Discover if the job matches a pre-configured image
        for (LabeledDockerImage image : configuration.getLabeledImages()) {
            ImmutableSet.Builder<LabelAtom> cloudImageLabels = ImmutableSet.<LabelAtom>builder()
                    .addAll(image.getLabels())
                    .addAll(getLabels());

            if (image.concatCondition(job.assignedLabel).matches(cloudImageLabels.build())) {
                return provisionJob(image.imageName, cloudImageLabels.add(new LabelAtom(IMAGE_LABEL_PREFIX + image.imageName)).build(), jobName, resetJob);
            }
        }

        return ProvisionResult.notSupported();
    }

    private ProvisionResult provisionJob(final String imageName, Set<LabelAtom> nodeLabels, String jobName, boolean resetJob) {
        for (final HostCount host : listAvailableHosts()) {
            try {
                LOG.fine(format("Provisioning node: host=%s capacity=%d", host.host, host.capacity));
                final DockerSlave slave = new DockerSlave(
                        format("%s (%s)", imageName, RandomStringUtils.randomAlphanumeric(6).toLowerCase()),
                        "Running job on image " + imageName,
                        "/",
                        nodeLabels,
                        new DockerComputerLauncher(new DockerJob(
                                host.host.getClient(),
                                jobName,
                                ImageName.parse(imageName),
                                getLaunchCommand(),
                                resetJob,
                                getDirectoryMappings()))
                );

                Jenkins.getInstance().addNode(slave);

                Computer.threadPoolForRemoting.submit(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Computer slaveComputer = slave.toComputer();
                            slaveComputer.connect(false).get();
                        } catch (Exception ex) {
                            LOG.log(Level.SEVERE, format("Error provisioning docker agent: [image=%s] [endpoint=%s]", imageName, host.host.getClient().getEndpoint()), ex);
                            throw Throwables.propagate(ex);
                        }
                    }
                });

                return ProvisionResult.provisioned(slave);
            } catch (Exception ex) {
                LOG.log(Level.WARNING, format("Error provisioning node: image=%s, host=%s", imageName, host.host), ex);
            }
        }

        return ProvisionResult.noCapacity();
    }

    /**
     * Find all docker servers with available capacity, sorted from greatest to least remaining
     * capacity.
     */
    private List<HostCount> listAvailableHosts() {
        Collection<DockerCloudHost> allHosts = listHosts();

        if (allHosts.size() == 0) {
            LOG.warning(format("No hosts found for docker cloud %s", getDisplayName()));
            return ImmutableList.of();
        }

        ArrayList<HostCount> availableHosts = newArrayListWithCapacity(allHosts.size());

        for (DockerCloudHost host : allHosts) {
            try {
                host.status(); // Query for status to check if host is available and ready

                int count = host.countRunningJobs();

                if (count < this.maxExecutors) {
                    availableHosts.add(new HostCount(host, this.maxExecutors - count));
                }
            } catch (Exception ex) {
                LOG.log(Level.WARNING, format("Error getting status from docker host %s", host), ex);
            }
        }

        // Sort from highest to lowest so we attempt to provision on least busy node first
        Collections.sort(availableHosts);

        return availableHosts;
    }

    protected DockerCloudHost buildDockerClient(String host) {
        try {
            URI dockerApiUri = URI.create(format("%s://%s:%d", tlsEnabled ? "https" : "http", host, this.dockerPort));
            SSLContext sslContext = tlsEnabled ? SSLContext.getInstance("TLS") : null;
            String username = null;
            String password = null;

            if (tlsEnabled && !isNullOrEmpty(credentialsId)) {
                StandardUsernamePasswordCredentials usernamePasswordCredentials = CredentialsMatchers.firstOrNull(
                        CredentialsProvider.lookupCredentials(
                                StandardUsernamePasswordCredentials.class,
                                Jenkins.getInstance(),
                                ACL.SYSTEM,
                                HTTPS_SCHEME),
                        CredentialsMatchers.withId(credentialsId)
                );

                if (usernamePasswordCredentials != null) {
                    username = usernamePasswordCredentials.getUsername();
                    password = usernamePasswordCredentials.getPassword().getPlainText();
                } else {
                    StandardCertificateCredentials certificateCredentials = CredentialsMatchers.firstOrNull(
                            CredentialsProvider.lookupCredentials(
                                    StandardCertificateCredentials.class,
                                    Jenkins.getInstance(),
                                    ACL.SYSTEM,
                                    HTTPS_SCHEME),
                            CredentialsMatchers.withId(credentialsId)
                    );

                    KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance("SunX509");
                    keyManagerFactory.init(certificateCredentials.getKeyStore(), certificateCredentials.getPassword().getPlainText().toCharArray());
                    sslContext.init(keyManagerFactory.getKeyManagers(), null, new SecureRandom());
                }
            }

            return new DockerCloudHost(new DockerClient(dockerApiUri, sslContext, ALLOW_ALL_HOSTNAMES, username, password));
        } catch (NoSuchAlgorithmException ex) {
            throw Throwables.propagate(ex);
        } catch (KeyManagementException ex) {
            throw Throwables.propagate(ex);
        } catch (KeyStoreException ex) {
            throw Throwables.propagate(ex);
        } catch (UnrecoverableKeyException ex) {
            throw Throwables.propagate(ex);
        }
    }

    private static List<DirectoryBinding> parseBindings(String bindingsString) {
        ImmutableList.Builder<DirectoryBinding> directoryBindings = ImmutableList.builder();
        int lineNum = 0;
        Set<String> containerDirs = newHashSet();

        if (!isNullOrEmpty(bindingsString)) {
            for (String line : bindingsString.split("[\r\n]+")) {
                lineNum += 1;
                line = cleanLine(line);

                if (line.length() > 0) {
                    String[] parts = line.split(":");

                    if (parts.length == 0 || parts.length > 3) {
                        throw new IllegalArgumentException(format("Invalid directory mapping (line %d): %s", lineNum, line));
                    }

                    String hostDir = parts[0].trim();
                    String containerDir = parts.length > 1 ? parts[1].trim() : null;
                    String accessStr = parts.length > 2 ? parts[2].trim().toLowerCase(Locale.US) : null;
                    DirectoryBinding.Access bindingAccess;

                    if (accessStr != null) {
                        bindingAccess = BINDING_ACCESS.get(accessStr);

                        if (bindingAccess == null) {
                            throw new IllegalArgumentException(format("Invalid directory mapping, unsupported access statement, use r or rw (line %d): %s", lineNum, line));
                        }
                    } else if (containerDir != null) {
                        bindingAccess = BINDING_ACCESS.get(containerDir.toLowerCase(Locale.US));

                        if (bindingAccess == null) {
                            bindingAccess = DirectoryBinding.Access.READ;
                        } else {
                            containerDir = hostDir;
                        }
                    } else {
                        containerDir = hostDir;
                        bindingAccess = DirectoryBinding.Access.READ;
                    }

                    if (!hostDir.startsWith("/") || !containerDir.startsWith("/")) {
                        throw new IllegalArgumentException(format("Invalid directory mapping, use absolute paths (line %d): %s", lineNum, line));
                    }

                    // Remove any suffix slashes from paths
                    hostDir = hostDir.replaceFirst("/+$", "");
                    containerDir = containerDir.replaceFirst("/+$", "");

                    // Check that container dir paths do not overlap
                    for (String otherContainerDir : containerDirs) {
                        if (containerDir.startsWith(otherContainerDir) || otherContainerDir.startsWith(containerDir)) {
                            throw new IllegalArgumentException(format("Container directories can not overlap (line %d): %s, %s", lineNum, line, otherContainerDir));
                        }
                    }

                    directoryBindings.add(new DirectoryBinding(hostDir, containerDir, bindingAccess));
                }
            }
        }

        return directoryBindings.build();
    }

    protected static String cleanLine(String line) {
        int commentIndex = line.indexOf('#');

        return commentIndex >= 0
                ? line.substring(0, commentIndex).trim()
                : line.trim();
    }

    public static abstract class Descriptor extends hudson.model.Descriptor<Cloud> {
        public ListBoxModel doFillCredentialsIdItems() {
            return new StandardListBoxModel()
                    .withEmptySelection()
                    .withMatching(
                            CredentialsMatchers.anyOf(
                                    CredentialsMatchers.instanceOf(StandardUsernamePasswordCredentials.class),
                                    CredentialsMatchers.instanceOf(StandardCertificateCredentials.class)
                            ),
                            CredentialsProvider.lookupCredentials(StandardCredentials.class,
                                    (Item) null,
                                    ACL.SYSTEM,
                                    HTTPS_SCHEME)
                    );
        }

        public FormValidation doCheckName(@QueryParameter String value) {
            return nullToEmpty(value).trim().length() == 0
                    ? FormValidation.error("Name is required")
                    : FormValidation.ok();
        }

        public FormValidation doCheckDirectoryMappingsString(@QueryParameter String value) {
            try {
                parseBindings(value);
                return FormValidation.ok();
            } catch (Exception ex) {
                return FormValidation.error(ex.getMessage());
            }
        }

        public FormValidation doCheckDockerPort(@QueryParameter int value) {
            return value < 1 || value > 65535
                    ? FormValidation.error("Invalid port value. Must be between 1 and 65535.")
                    : FormValidation.ok();
        }

        public FormValidation doCheckMaxExecutors(@QueryParameter int value) {
            return value < 1
                    ? FormValidation.error("Invalid limit value. Must be greater than or equal to 1.")
                    : FormValidation.ok();
        }

        public FormValidation doCheckSlaveJarPath(@QueryParameter String value) {
            String result = nullToEmpty(value).trim();
            return result.length() > 0 && result.charAt(0) != '/'
                    ? FormValidation.error("Path must be absolute")
                    : FormValidation.ok();
        }
    }

    private static final class HostCount implements Comparable<HostCount> {
        public final int capacity;
        public final DockerCloudHost host;

        public HostCount(DockerCloudHost host, int capacity) {
            this.host = host;
            this.capacity = capacity;
        }

        public int compareTo(final HostCount hostCount) {
            // Sort from highest to lowest
            return hostCount.capacity - this.capacity;
        }
    }

    private static String extractImageName(LabelAtom imageLabel) {
        return imageLabel.toString().substring(IMAGE_LABEL_PREFIX.length());
    }

    public Iterable<LabelAtom> listPotentialImages(Label jobLabel) {
        return Iterables.filter(jobLabel.listAtoms(), new Predicate<LabelAtom>() {
            @Override
            public boolean apply(@Nullable LabelAtom input) {
                if (input == null) {
                    return false;
                }

                String atomStr = input.toString();
                return atomStr.startsWith(IMAGE_LABEL_PREFIX) && atomStr.length() > IMAGE_LABEL_PREFIX.length();
            }
        });
    }

    private static final HostnameVerifier ALLOW_ALL_HOSTNAMES = new HostnameVerifier() {
        @Override
        public boolean verify(String s, SSLSession sslSession) {
            return true;
        }
    };
}