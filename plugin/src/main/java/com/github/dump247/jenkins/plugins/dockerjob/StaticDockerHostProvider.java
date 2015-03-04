package com.github.dump247.jenkins.plugins.dockerjob;

import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.google.common.net.HostAndPort;
import hudson.Extension;
import hudson.util.FormValidation;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import java.util.Collection;
import java.util.List;

import static com.google.common.collect.Lists.newArrayList;

/**
 * Host provider that simply returns a static list of hosts.
 */
public class StaticDockerHostProvider extends DockerHostProvider {
    private static final Splitter SPLITTER = Splitter.on(CharMatcher.anyOf(", \n")).trimResults().omitEmptyStrings();

    private final String _hostString;

    private transient List<HostAndPort> _hosts;

    @DataBoundConstructor
    public StaticDockerHostProvider(String hostString) {
        _hostString = hostString;
        readResolve();
    }

    protected Object readResolve() {
        _hosts = parseHosts(_hostString);
        return this;
    }

    public String getHostString() {
        return _hostString;
    }

    private static List<HostAndPort> parseHosts(String value) {
        List<HostAndPort> hosts = newArrayList();

        for (String host : SPLITTER.split(value)) {
            hosts.add(HostAndPort.fromString(host));
        }

        return hosts;
    }

    @Override
    public Collection<HostAndPort> listHosts() throws Exception {
        return _hosts;
    }

    @Extension
    public static class Descriptor extends DockerHostProvider.Descriptor {
        @Override
        public String getDisplayName() {
            return "Static";
        }

        public FormValidation doCheckHostString(@QueryParameter String value) {
            try {
                List<HostAndPort> hosts = parseHosts(value);

                if (hosts.size() == 0) {
                    return FormValidation.error("At least one host is required");
                }

                return FormValidation.ok();
            } catch (Exception ex) {
                return FormValidation.error(ex.getMessage());
            }
        }
    }
}
