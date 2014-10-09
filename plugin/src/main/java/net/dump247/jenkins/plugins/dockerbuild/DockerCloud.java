package net.dump247.jenkins.plugins.dockerbuild;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCertificateCredentials;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.SchemeRequirement;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.filter.HTTPBasicAuthFilter;
import com.sun.jersey.client.urlconnection.HTTPSProperties;
import hudson.Extension;
import hudson.model.Describable;
import hudson.model.Item;
import hudson.model.Label;
import hudson.model.labels.LabelAtom;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import net.dump247.docker.DockerClient;
import org.kohsuke.stapler.DataBoundConstructor;
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
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.base.Strings.nullToEmpty;
import static com.google.common.collect.Lists.newArrayListWithCapacity;
import static java.lang.String.format;
import static java.util.Collections.unmodifiableSet;

/**
 * Set of machines to run jenkins slave docker containers on.
 */
public class DockerCloud implements Describable<DockerCloud> {
    private static final Logger LOG = Logger.getLogger(DockerCloud.class.getName());
    private static final SchemeRequirement HTTPS_SCHEME = new SchemeRequirement("https");

    public final String hostString;
    private transient List<DockerCloudHost> _hosts;

    public final int dockerPort;

    public final String labelString;
    private transient Set<LabelAtom> _labels;

    public final int maxExecutors;

    public final boolean tlsEnabled;
    public final String credentialsId;

    @DataBoundConstructor
    public DockerCloud(final String hostString, final int dockerPort, final String labelString, final int maxExecutors, final boolean tlsEnabled, final String credentialsId) {
        this.hostString = hostString;
        this.dockerPort = dockerPort;
        this.labelString = labelString;
        this.maxExecutors = maxExecutors;
        this.tlsEnabled = tlsEnabled;
        this.credentialsId = credentialsId;

        readResolve();
    }

    /**
     * Initialize transient fields after deserialization.
     */
    protected Object readResolve() {
        LOG.info(format("Credentials: %s %s", tlsEnabled, credentialsId));

        ImmutableList.Builder<DockerCloudHost> dockerHosts = ImmutableList.builder();

        for (String host : nullToEmpty(this.hostString).split("[,\\s]+")) {
            dockerHosts.add(new DockerCloudHost(buildDockerClient(host)));
        }

        _hosts = dockerHosts.build();

        _labels = unmodifiableSet(Label.parse(this.labelString));

        return this;
    }

    @SuppressWarnings("unchecked")
    public hudson.model.Descriptor<DockerCloud> getDescriptor() {
        return Jenkins.getInstance().getDescriptor(getClass());
    }

    private DockerClient buildDockerClient(String host) {
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

    public List<DockerCloudHost> getHosts() {
        return _hosts;
    }

    public Set<LabelAtom> getLabels() {
        return _labels;
    }

    public ProvisionResult provisionJob(Label jobLabel, String imageName, Set<LabelAtom> imageLabels) {
        checkNotNull(jobLabel);
        checkNotNull(imageName);
        checkNotNull(imageLabels);

        Set<LabelAtom> cloudImageLabels = Sets.union(imageLabels, getLabels());

        if (!jobLabel.matches(cloudImageLabels)) {
            return ProvisionResult.notSupported();
        }

        for (HostCount host : listAvailableHosts()) {
            try {
                LOG.info(format("Provisioning node: [host=%s] [load=%d]", host.host, host.count));
                return ProvisionResult.provisioned(host.host.provisionSlave(imageName, cloudImageLabels));
            } catch (IOException ex) {
                LOG.log(Level.WARNING, format("Error provisioning node: [host=%s] [load=%d]", host.host, host.count), ex);
            }
        }

        return ProvisionResult.noCapacity();
    }

    /**
     * Find all docker servers with available capacity, sorted from greatest to least remaining
     * capacity.
     */
    private List<HostCount> listAvailableHosts() {
        ArrayList<HostCount> availableHosts = newArrayListWithCapacity(_hosts.size());

        for (DockerCloudHost host : _hosts) {
            int count = host.countRunningJobs();

            if (count < this.maxExecutors) {
                availableHosts.add(new HostCount(host, count));
            }
        }

        // Sort from lowest to highest load so we attempt to provision on least busy node first
        Collections.sort(availableHosts);

        return availableHosts;
    }

    @Extension
    public static final class Descriptor extends hudson.model.Descriptor<DockerCloud> {
        @Override
        public String getDisplayName() {
            return "Docker Cloud";
        }

        public ListBoxModel doFillCredentialsIdItems() {
            LOG.info("(cloud) Filling credentials ids");

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
            return tlsEnabled && isNullOrEmpty(value)
                    ? FormValidation.error("TLS is required when authentication is enabled")
                    : FormValidation.ok();
        }
    }

    private static final class HostCount implements Comparable<HostCount> {
        public final int count;
        public final DockerCloudHost host;

        public HostCount(DockerCloudHost host, int count) {
            this.host = host;
            this.count = count;
        }

        public int compareTo(final HostCount hostCount) {
            // Sort from lowest to highest count
            return this.count - hostCount.count;
        }
    }

    private static final HostnameVerifier ALLOW_ALL_HOSTNAMES = new HostnameVerifier() {
        @Override
        public boolean verify(String s, SSLSession sslSession) {
            return true;
        }
    };
}
