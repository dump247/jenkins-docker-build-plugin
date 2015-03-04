package com.github.dump247.jenkins.plugins.dockerjob;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.github.dump247.jenkins.plugins.dockerjob.slaves.DirectoryMapping;
import com.github.dump247.jenkins.plugins.dockerjob.slaves.SlaveClient;
import com.github.dump247.jenkins.plugins.dockerjob.slaves.SlaveOptions;
import com.github.dump247.jenkins.plugins.dockerjob.util.ConfigUtil;
import com.github.dump247.jenkins.plugins.dockerjob.util.SshCredentialsProvider;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.base.Throwables;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import com.google.common.net.HostAndPort;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.inject.Provider;
import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.Computer;
import hudson.model.Item;
import hudson.model.Label;
import hudson.model.labels.LabelAtom;
import hudson.model.queue.MappingWorksheet;
import hudson.security.ACL;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import org.joda.time.Duration;
import org.joda.time.Instant;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import static com.github.dump247.jenkins.plugins.dockerjob.util.JenkinsUtils.getNodes;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.base.Strings.nullToEmpty;
import static com.google.common.collect.Iterables.getFirst;
import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Lists.newArrayListWithCapacity;
import static com.google.common.collect.Maps.newHashMap;
import static java.lang.String.format;
import static java.util.Collections.lastIndexOfSubList;
import static java.util.Collections.unmodifiableSet;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.FINER;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

/**
 * Cloud that maps Jenkins jobs to a docker cluster.
 * <p/>
 * This cloud implementation differs from the normal implementation in that it does not directly
 * provision jenkins nodes. To ensure that a specific job gets mapped to a specific slave, the
 * actual provisioning is done in {@link DockerJobLoadBalancer}. This cloud implementation serves
 * several important purposes:
 * <ul>
 * <li>Ensure the "Restrict where this project can be run" option is visible in job configuration</li>
 * <li>Validate that the value in "Restrict..." is valid</li>
 * <li>Makes the plugin fit into the standard jenkins configuration section (under <em>Cloud</em> in system settings)</li>
 * </ul>
 */
public class DockerJobCloud extends Cloud {
    private static final Duration HOSTS_REFRESH_INTERVAL = Duration.standardSeconds(30);
    private static final Logger LOG = Logger.getLogger(DockerJobCloud.class.getName());
    private static final ListeningExecutorService EXECUTOR = MoreExecutors.listeningDecorator(
            Executors.newFixedThreadPool(
                    5,
                    new ThreadFactoryBuilder()
                            .setNameFormat("docker-job-%d")
                            .setDaemon(true)
                            .build()));

    private final DockerHostProvider _hostProvider;
    private final int _sshPort;
    private final String _credentialsId;
    private final int _maxJobsPerHost;
    private final String _labelString;
    private final String _requiredLabelString;
    private final String _directoryMappingString;
    private final String _slaveInitScript;

    private transient Jenkins _jenkins;
    private transient Instant _nextHostsRefresh;
    private transient Map<HostAndPort, HostState> _hosts;
    private transient Throwable _hostProviderError;
    private transient Provider<StandardUsernameCredentials> _credentialsProvider;
    private transient Set<LabelAtom> _labels;
    private transient Set<LabelAtom> _requiredLabels;
    private transient List<DirectoryMapping> _directoryMappings;

    @DataBoundConstructor
    public DockerJobCloud(String name, DockerHostProvider hostProvider, int sshPort,
                          String credentialsId, int maxJobsPerHost,
                          String labelString, String requiredLabelString,
                          String directoryMappingString,
                          String slaveInitScript) {
        super(name);

        _hostProvider = checkNotNull(hostProvider);
        _sshPort = sshPort;
        _credentialsId = credentialsId;
        _maxJobsPerHost = maxJobsPerHost;
        _labelString = nullToEmpty(labelString);
        _requiredLabelString = nullToEmpty(requiredLabelString);
        _directoryMappingString = nullToEmpty(directoryMappingString);
        _slaveInitScript = nullToEmpty(slaveInitScript);

        checkArgument(sshPort >= 1 && sshPort <= 65535);
        checkArgument(maxJobsPerHost > 0);

        readResolve();
    }

    protected Object readResolve() {
        _nextHostsRefresh = new Instant(0);
        _jenkins = Jenkins.getInstance();
        _hosts = newHashMap();
        _credentialsProvider = new SshCredentialsProvider(_jenkins, _credentialsId);
        _labels = unmodifiableSet(Label.parse(_labelString));
        _requiredLabels = unmodifiableSet(Label.parse(_requiredLabelString));
        _directoryMappings = parseDirectoryMappings(_directoryMappingString);
        return this;
    }

    public DockerHostProvider getHostProvider() {
        return _hostProvider;
    }

    public int getSshPort() {
        return _sshPort;
    }

    public String getCredentialsId() {
        return _credentialsId;
    }

    public int getMaxJobsPerHost() {
        return _maxJobsPerHost;
    }

    public String getLabelString() {
        return _labelString;
    }

    public String getRequiredLabelString() {
        return _requiredLabelString;
    }

    public String getDirectoryMappingString() {
        return _directoryMappingString;
    }

    public String getSlaveInitScript() {
        return _slaveInitScript;
    }

    @Override
    public Collection<NodeProvisioner.PlannedNode> provision(Label label, int excessWorkload) {
        // Don't provision a node here. Provisioning is handled in DockerJobLoadBalancer.
        return ImmutableList.of();
    }

    @Override
    public boolean canProvision(Label label) {
        LOG.log(FINER, "canProvision({0})", label);
        return validateJob(label).isPresent();
    }

    public ProvisionResult provisionJob(final String jobName, AbstractProject job, MappingWorksheet.WorkChunk task) throws Exception {
        JobValidationResult result = validateJob(task.assignedLabel).orNull();

        if (result == null) {
            return ProvisionResult.NOT_SUPPORTED;
        }

        DockerJobProperty jobConfig = (DockerJobProperty) job.getProperty(DockerJobProperty.class);
        final String imageName = getImageName(jobConfig, result);
        boolean resetJob = false;
        Map<String, String> jobEnv = ImmutableMap.of();

        if (jobConfig != null) {
            resetJob = jobConfig.resetJobEnabled();
            jobEnv = jobConfig.getEnvironmentVars();
        }

        if (isNullOrEmpty(imageName)) {
            throw new RuntimeException(format("Unable to find docker image for job %s", jobName));
        }

        final SlaveClient host = getFirst(listAvailableHosts(), null);
        if (host == null) {
            return ProvisionResult.NO_CAPACITY;
        }

        // Provision DockerJobSlave
        SlaveOptions options = new SlaveOptions(jobName, imageName);
        options.setCleanEnvironment(resetJob);
        options.setEnvironment(jobEnv);
        options.setDirectoryMappings(_directoryMappings);

        final DockerJobSlave slave = new DockerJobSlave(
                jobName,
                "Job running in docker container",
                "/",
                ImmutableSet.<LabelAtom>builder()
                        .addAll(result.labels)
                        .add(new LabelAtom("image/" + imageName))
                        .build(),
                new DockerJobComputerLauncher(host, options));

        _jenkins.addNode(slave);

        Computer.threadPoolForRemoting.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    Computer slaveComputer = slave.toComputer();
                    slaveComputer.connect(false).get();
                } catch (Exception ex) {
                    LOG.log(SEVERE, format("Error provisioning docker slave: job=%s image=%s endpoint=%s", jobName, imageName, host.getHost()), ex);
                    throw Throwables.propagate(ex);
                }
            }
        });

        return ProvisionResult.SUCCESS;
    }

    private static String getImageName(DockerJobProperty jobConfig, JobValidationResult result) {
        if (jobConfig != null && !isNullOrEmpty(jobConfig.imageName)) {
            return jobConfig.imageName;
        }

        if (!isNullOrEmpty(result.imageName)) {
            return result.imageName;
        }

        return null;
    }

    private Optional<JobValidationResult> validateJob(Label label) {
        Set<LabelAtom> allLabels = Sets.union(_requiredLabels, _labels);

        if (label == null) {
            if (_requiredLabels.size() > 0) {
                LOG.log(FINE, "Condition does not include required labels: condition={0} cloud={1} required={2}", new Object[]{label, getDisplayName(), _requiredLabels});
                return Optional.absent();
            } else {
                LOG.log(FINE, "Condition matched cloud labels: condition={0} cloud={1} labels={2}", new Object[]{label, getDisplayName(), allLabels});
                return Optional.of(new JobValidationResult(allLabels, null));
            }
        }

        // Check that the condition includes all the required atoms
        if (!label.listAtoms().containsAll(_requiredLabels)) {
            LOG.log(FINE, "Condition does not include required labels: condition={0} cloud={1} required={2}", new Object[]{label, getDisplayName(), _requiredLabels});
            return Optional.absent();
        }

        // Check if the condition matches the cloud's labels
        if (label.matches(allLabels)) {
            LOG.log(FINE, "Condition matched cloud labels: condition={0} cloud={1} labels={2}", new Object[]{label, getDisplayName(), allLabels});
            return Optional.of(new JobValidationResult(allLabels, null));
        }

        // Check if the condition matches the cloud's labels combined with a specific image
        DockerJobGlobalConfiguration config = DockerJobGlobalConfiguration.get();

        for (LabeledDockerImage image : config.getLabeledImages()) {
            Set<LabelAtom> imageLabels = Sets.union(image.getLabels(), allLabels);

            if (label.matches(imageLabels)) {
                LOG.log(FINE, "Condition matched cloud+image labels: condition={0} cloud={1} image={2} labels={3}", new Object[]{label, getDisplayName(), image.imageName, imageLabels});
                return Optional.of(new JobValidationResult(imageLabels, image.imageName));
            } else {
                LOG.log(FINE, "Condition does not match cloud+image labels: condition={0} cloud={1} image={2} labels={3}", new Object[]{label, getDisplayName(), image.imageName, imageLabels});
            }
        }

        LOG.log(FINE, "Condition does not match cloud: condition={0} cloud={1} labels={2} images={3}", new Object[]{label, getDisplayName(), allLabels, config.getLabeledImages().size()});
        return Optional.absent();
    }

    /**
     * List hosts in this cloud that have capacity to launch a job. Hosts are listed from greatest
     * to least available capacity.
     */
    private List<SlaveClient> listAvailableHosts() {
        Iterable<SlaveClient> successfulHosts = FluentIterable.from(listHosts())
                .filter(SUCCESSFUL_HOSTS)
                .transform(GET_CLIENT);
        Map<HostAndPort, CapacityCount> cloudHosts = newHashMap();

        for (SlaveClient host : successfulHosts) {
            cloudHosts.put(host.getHost(), new CapacityCount(host, _maxJobsPerHost));
        }

        for (DockerJobSlave slave : getNodes(_jenkins, DockerJobSlave.class)) {
            CapacityCount count = cloudHosts.get(slave.getLauncher().getHost());

            if (count != null) {
                count.remaining -= 1;
            }
        }

        return FluentIterable.from(CAPACITY_ORDER.sortedCopy(cloudHosts.values()))
                .filter(HAS_CAPACITY)
                .transform(GET_CLIENT2)
                .toList();
    }

    private Collection<HostState> listHosts() {
        Instant now = Instant.now();

        if (now.isAfter(_nextHostsRefresh)) {
            _nextHostsRefresh = now.plus(HOSTS_REFRESH_INTERVAL);

            try {
                Map<HostAndPort, HostState> newHosts = newHashMap();
                Collection<HostAndPort> hosts = _hostProvider.listHosts();
                List<ListenableFuture<HostState>> hostFutures = newArrayListWithCapacity(hosts.size());

                for (HostAndPort host : hosts) {
                    final HostAndPort targetHost = host.withDefaultPort(_sshPort);

                    HostState currentState = _hosts.get(targetHost);

                    if (currentState == null || currentState.status == HostStatus.FAILED) {
                        hostFutures.add(EXECUTOR.submit(new Callable<HostState>() {
                            @Override
                            public HostState call() throws Exception {
                                SlaveClient client = null;
                                try {
                                    client = new SlaveClient(targetHost, _credentialsProvider);
                                    String description = client.initialize(
                                            _jenkins.getJnlpJars("slave.jar").getURL(),
                                            nullToEmpty(_slaveInitScript));
                                    return HostState.success(targetHost, description, client);
                                } catch (Exception ex) {
                                    if (client != null) {
                                        client.close();
                                    }

                                    return HostState.failed(targetHost, ex);
                                }
                            }
                        }));
                    } else {
                        newHosts.put(targetHost, currentState);
                    }
                }

                if (hostFutures.size() > 0) {
                    try {
                        for (HostState state : Futures.allAsList(hostFutures).get()) {
                            if (state.status == HostStatus.FAILED) {
                                LOG.log(WARNING, "Error connecting to cloud host: host={0} error={1}", new Object[]{state.host, state.message});
                            }

                            newHosts.put(state.host, state);
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    } catch (ExecutionException ex) {
                        throw Throwables.propagate(ex);
                    }
                }

                _hosts = newHosts;
                _hostProviderError = null;
            } catch (Throwable ex) {
                _hosts = newHashMap();
                _hostProviderError = ex;
            }
        }

        return _hosts.values();
    }

    private static List<DirectoryMapping> parseDirectoryMappings(String value) {
        List<DirectoryMapping> mappings = newArrayList();

        for (ConfigUtil.ConfigLine line : ConfigUtil.splitConfigLines(value)) {
            try {
                mappings.add(DirectoryMapping.parse(line.value));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException(format("Invalid directory mapping (line %d): %s", line.lineNum, line.value));
            }
        }

        return mappings;
    }

    @Extension
    public static class Descriptor extends hudson.model.Descriptor<Cloud> {
        @Override
        public String getDisplayName() {
            return "Docker Job";
        }

        public ListBoxModel doFillCredentialsIdItems() {
            return new StandardListBoxModel()
                    .withEmptySelection()
                    .withMatching(
                            CredentialsMatchers.anyOf(
                                    CredentialsMatchers.instanceOf(StandardUsernamePasswordCredentials.class),
                                    CredentialsMatchers.instanceOf(SSHUserPrivateKey.class)
                            ),
                            CredentialsProvider.lookupCredentials(
                                    StandardUsernameCredentials.class,
                                    (Item) null,
                                    ACL.SYSTEM,
                                    SshCredentialsProvider.SSH_SCHEME)
                    );
        }

        public FormValidation doCheckRequiredLabelString(@QueryParameter String value) {
            if (isNullOrEmpty(value)) {
                return FormValidation.ok();
            }

            try {
                Label.parse(value);
                return FormValidation.ok();
            } catch (Throwable ex) {
                return FormValidation.error(ex.getMessage());
            }
        }

        public FormValidation doCheckLabelString(@QueryParameter String value, @QueryParameter String requiredLabelString) {
            if (isNullOrEmpty(value)) {
                return FormValidation.ok();
            }

            Set<LabelAtom> labels;

            try {
                labels = Label.parse(value);
            } catch (Throwable ex) {
                return FormValidation.error(ex.getMessage());
            }

            if (labels.size() > 0) {
                try {
                    Set<LabelAtom> requiredLabels = Label.parse(requiredLabelString);

                    if (Sets.union(labels, requiredLabels).size() != labels.size() + requiredLabels.size()) {
                        return FormValidation.error("Contains duplicate label in required list");
                    }
                } catch (Throwable ex) {
                    // Ignore. Validation failures will be handled by doCheckRequiredLabelString.
                }
            }

            return FormValidation.ok();
        }

        public FormValidation doCheckCredentialsId(@QueryParameter String value) {
            return nullToEmpty(value).trim().length() > 0
                    ? FormValidation.ok()
                    : FormValidation.error("Required");
        }

        public FormValidation doCheckName(@QueryParameter String value) {
            return nullToEmpty(value).trim().length() > 0
                    ? FormValidation.ok()
                    : FormValidation.error("Required");
        }

        public FormValidation doCheckMaxJobsPerHost(@QueryParameter int value) {
            return value >= 1
                    ? FormValidation.ok()
                    : FormValidation.error("Must be greater than 0");
        }

        public FormValidation doCheckSshPort(@QueryParameter int value) {
            return value >= 1 && value <= 65535
                    ? FormValidation.ok()
                    : FormValidation.error("Must be between 1 and 65535");
        }

        public FormValidation doCheckDirectoryMappingString(@QueryParameter String value) {
            try {
                parseDirectoryMappings(value);
                return FormValidation.ok();
            } catch (Exception ex) {
                return FormValidation.error(ex.getMessage());
            }
        }
    }

    public static enum ProvisionResult {
        /**
         * The cloud can not provision a slave for the job because the job restrictions/conditions
         * do not match the cloud properties.
         */
        NOT_SUPPORTED,

        /**
         * The job can be run on the cloud, but the cloud does not currently have the available
         * capacity.
         */
        NO_CAPACITY,

        /**
         * A slave was provisioned and the job will run in the cloud.
         */
        SUCCESS
    }

    private static class HostState {
        public final HostAndPort host;
        public final HostStatus status;
        public final String message;
        public final SlaveClient client;

        public HostState(HostAndPort host, HostStatus status, String message, SlaveClient client) {
            this.host = host;
            this.status = status;
            this.message = message;
            this.client = client;
        }

        public static HostState failed(HostAndPort host, Throwable error) {
            return new HostState(host, HostStatus.FAILED, error.getMessage(), null);
        }

        public static HostState success(HostAndPort host, String message, SlaveClient client) {
            return new HostState(host, HostStatus.SUCCESS, message, client);
        }
    }

    private static class CapacityCount {
        public final SlaveClient client;
        public int remaining;

        public CapacityCount(SlaveClient client, int capacity) {
            this.client = client;
            this.remaining = capacity;
        }
    }

    private static class JobValidationResult {
        private final Set<LabelAtom> labels;
        private final String imageName;

        public JobValidationResult(Set<LabelAtom> labels, String imageName) {
            this.labels = labels;
            this.imageName = imageName;
        }
    }

    private static enum HostStatus {
        FAILED,
        SUCCESS
    }

    private static final Predicate<HostState> SUCCESSFUL_HOSTS = new Predicate<HostState>() {
        public boolean apply(HostState input) {
            return input.status == HostStatus.SUCCESS;
        }
    };

    private static final Function<SlaveClient, HostAndPort> GET_HOST = new Function<SlaveClient, HostAndPort>() {
        public HostAndPort apply(SlaveClient input) {
            return input.getHost();
        }
    };

    private static final Function<HostState, SlaveClient> GET_CLIENT = new Function<HostState, SlaveClient>() {
        public SlaveClient apply(HostState input) {
            return input.client;
        }
    };

    private static final Predicate<CapacityCount> HAS_CAPACITY = new Predicate<CapacityCount>() {
        public boolean apply(CapacityCount input) {
            return input.remaining > 0;
        }
    };

    private static final Function<CapacityCount, SlaveClient> GET_CLIENT2 = new Function<CapacityCount, SlaveClient>() {
        public SlaveClient apply(CapacityCount input) {
            return input.client;
        }
    };

    private static final Ordering<CapacityCount> CAPACITY_ORDER = Ordering.from(new Comparator<CapacityCount>() {
        public int compare(CapacityCount o1, CapacityCount o2) {
            return Integer.compare(o2.remaining, o1.remaining);
        }
    });
}
