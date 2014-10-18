package net.dump247.jenkins.plugins.dockerbuild;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCertificateCredentials;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.SchemeRequirement;
import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Item;
import hudson.model.Label;
import hudson.model.labels.LabelAtom;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import net.dump247.docker.DirectoryBinding;
import net.dump247.docker.DockerClient;
import net.dump247.jenkins.plugins.dockerbuild.log.Logger;
import org.kohsuke.stapler.QueryParameter;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import java.io.IOException;
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
import java.util.Map;
import java.util.Set;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.collect.Lists.newArrayListWithCapacity;
import static java.lang.String.format;
import static java.util.Collections.unmodifiableSet;

/**
 * Cloud of docker servers to run jenkins jobs on.
 */
public abstract class DockerCloud extends AbstractDescribableImpl<DockerCloud> {
    private static final Logger LOG = Logger.get(DockerCloud.class);
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

    protected DockerCloud(final int dockerPort, final String labelString, final int maxExecutors, final boolean tlsEnabled, final String credentialsId, final String directoryMappingsString) {
        this.dockerPort = dockerPort;
        this.labelString = labelString;
        this.maxExecutors = maxExecutors;
        this.tlsEnabled = tlsEnabled;
        this.credentialsId = credentialsId;
        this.directoryMappingsString = directoryMappingsString;
    }

    public abstract Collection<DockerCloudHost> listHosts();

    public Set<LabelAtom> getLabels() {
        return _labels;
    }

    /**
     * Initialize transient fields after deserialization.
     */
    protected Object readResolve() {
        _labels = unmodifiableSet(Label.parse(this.labelString));
        _directoryMappings = parseBindings(directoryMappingsString);
        return this;
    }

    public ProvisionResult provisionJob(Optional<Label> jobLabel, String imageName, Set<LabelAtom> imageLabels) {
        checkNotNull(imageName);
        checkNotNull(imageLabels);

        Set<LabelAtom> cloudImageLabels = Sets.union(imageLabels, getLabels());

        if (jobLabel.isPresent() && !jobLabel.get().matches(cloudImageLabels)) {
            return ProvisionResult.notSupported();
        }

        for (HostCount host : listAvailableHosts()) {
            try {
                LOG.info("Provisioning node: host={0} capacity={1}", host.host, host.capacity);
                return ProvisionResult.provisioned(host.host.provisionSlave(imageName, cloudImageLabels, _directoryMappings));
            } catch (IOException ex) {
                LOG.warn("Error provisioning node: host={0}", host.host, ex);
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
        ArrayList<HostCount> availableHosts = newArrayListWithCapacity(allHosts.size());

        for (DockerCloudHost host : allHosts) {
            int count = host.countRunningJobs();

            if (count < this.maxExecutors) {
                availableHosts.add(new HostCount(host, this.maxExecutors - count));
            }
        }

        // Sort from highest to lowest so we attempt to provision on least busy node first
        Collections.sort(availableHosts);

        return availableHosts;
    }

    protected DockerClient buildDockerClient(String host) {
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

            return new DockerClient(dockerApiUri, sslContext, ALLOW_ALL_HOSTNAMES, username, password);
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
                    String accessStr = parts.length > 2 ? parts[2].trim().toLowerCase() : null;
                    DirectoryBinding.Access bindingAccess;

                    if (accessStr != null) {
                        bindingAccess = BINDING_ACCESS.get(accessStr);

                        if (bindingAccess == null) {
                            throw new IllegalArgumentException(format("Invalid directory mapping, unsupported access statement, use r or rw (line %d): %s", lineNum, line));
                        }
                    } else if (containerDir != null) {
                        bindingAccess = BINDING_ACCESS.get(containerDir.toLowerCase());

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

                    directoryBindings.add(new DirectoryBinding(hostDir, containerDir, bindingAccess));
                }
            }
        }

        return directoryBindings.build();
    }

    private static String cleanLine(String line) {
        int commentIndex = line.indexOf('#');

        return commentIndex >= 0
                ? line.substring(0, commentIndex).trim()
                : line.trim();
    }

    public static abstract class Descriptor extends hudson.model.Descriptor<DockerCloud> {
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

        public FormValidation doCheckCredentialsId(@QueryParameter String value, @QueryParameter boolean tlsEnabled) {
            return !tlsEnabled && !isNullOrEmpty(value)
                    ? FormValidation.error("TLS is required when authentication is enabled")
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

    private static final HostnameVerifier ALLOW_ALL_HOSTNAMES = new HostnameVerifier() {
        @Override
        public boolean verify(String s, SSLSession sslSession) {
            return true;
        }
    };
}
