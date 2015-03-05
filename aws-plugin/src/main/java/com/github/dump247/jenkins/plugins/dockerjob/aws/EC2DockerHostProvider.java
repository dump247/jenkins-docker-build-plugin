package com.github.dump247.jenkins.plugins.dockerjob.aws;

import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.DescribeInstanceStatusRequest;
import com.amazonaws.services.ec2.model.DescribeInstanceStatusResult;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.Filter;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.InstanceNetworkInterface;
import com.amazonaws.services.ec2.model.InstanceStatus;
import com.amazonaws.services.ec2.model.Reservation;
import com.github.dump247.jenkins.plugins.dockerjob.DockerHostProvider;
import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.google.common.collect.Iterables;
import com.google.common.net.HostAndPort;
import hudson.Extension;
import hudson.util.FormValidation;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.nullToEmpty;
import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Sets.newHashSet;
import static java.lang.String.format;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.FINER;
import static java.util.logging.Level.WARNING;

/**
 * Uses AWS APIs to discover EC2 instance hosts.
 */
public class EC2DockerHostProvider extends DockerHostProvider {
    private static final Logger LOG = Logger.getLogger(EC2DockerHostProvider.class.getName());
    private static final Splitter COMMA_SPLITTER = Splitter.on(',');
    private static final Splitter SPLITTER = Splitter.on(CharMatcher.anyOf(", \n")).trimResults().omitEmptyStrings();

    private final String _filterString;
    private final String _regionString;
    private final boolean _usePublicIP;
    private final int _interfaceIndex;

    private transient List<Filter> _filters;
    private transient List<AmazonEC2> _amazonEC2;

    @DataBoundConstructor
    public EC2DockerHostProvider(String filterString, String regionString, boolean usePublicIP, int interfaceIndex) {
        _filterString = filterString;
        _regionString = regionString;
        _usePublicIP = usePublicIP;
        _interfaceIndex = interfaceIndex;
        readResolve();
    }

    protected Object readResolve() {
        _filters = parseFilters(_filterString);
        _amazonEC2 = newArrayList();

        for (Region region : parseRegions(_regionString)) {
            AmazonEC2 ec2 = new AmazonEC2Client();
            ec2.setRegion(region);
            LOG.log(FINER, "Region enabled: {0}", region);
            _amazonEC2.add(ec2);
        }

        return this;
    }

    public String getFilterString() {
        return _filterString;
    }

    public String getRegionString() {
        return _regionString;
    }

    public boolean isUsePublicIP() {
        return _usePublicIP;
    }

    public int getInterfaceIndex() {
        return _interfaceIndex;
    }

    @Override
    public Collection<HostAndPort> listHosts() throws Exception {
        List<HostAndPort> hosts = newArrayList();

        for (AmazonEC2 client : _amazonEC2) {
            try {
                hosts.addAll(listHosts(client));
            } catch (Throwable ex) {
                LOG.log(WARNING, "Error listing EC2 hosts", ex);
            }
        }

        LOG.log(FINE, "Discovered {0} instances: filters={1}", new Object[]{hosts.size(), _filters});

        return hosts;
    }

    private List<HostAndPort> listHosts(AmazonEC2 client) throws Exception {
        List<HostAndPort> hosts = newArrayList();
        DescribeInstancesRequest request = new DescribeInstancesRequest().withFilters(_filters);
        String nextToken = null;

        LOG.log(FINER, "Discovering instances: filters={0}", new Object[]{_filters});

        do {
            DescribeInstancesResult result = client.describeInstances(request.withNextToken(nextToken));

            for (Reservation reservation : result.getReservations()) {
                for (Instance instance : reservation.getInstances()) {
                    LOG.log(FINER, "Instance state: {0} {1}", new Object[]{instance.getInstanceId(), instance.getState().getName()});

                    if ("running".equalsIgnoreCase(instance.getState().getName())) {
                        List<InstanceNetworkInterface> networkInterfaces = instance.getNetworkInterfaces();

                        if (_interfaceIndex >= networkInterfaces.size()) {
                            LOG.log(WARNING, "No interface {0} found on instance {1}", new Object[]{_interfaceIndex, instance.getInstanceId()});
                        } else {
                            InstanceNetworkInterface nic = networkInterfaces.get(_interfaceIndex);
                            String ip;

                            if (_usePublicIP) {
                                ip = nic.getAssociation().getPublicIp();
                            } else {
                                ip = nic.getPrivateIpAddress();
                            }

                            hosts.add(HostAndPort.fromString(ip));
                        }
                    }
                }
            }

            nextToken = result.getNextToken();
        } while (nextToken != null);

        return hosts;
    }

    private static List<Filter> parseFilters(String value) {
        List<Filter> filters = newArrayList();

        for (String filter : SPLITTER.split(value)) {
            String[] nameValues = filter.split("=", 2);
            checkArgument(nameValues.length == 2, format("Filter must be 'name=value[,value,value]: %s", filter));

            String filterName = nameValues[0].trim();
            checkArgument(filterName.length() > 0, format("Filter must be 'name=value[,value,value]: %s", filter));

            filters.add(new Filter()
                    .withName(filterName)
                    .withValues(Iterables.toArray(COMMA_SPLITTER.split(nameValues[1]), String.class)));
        }

        return filters;
    }

    private static Set<Region> parseRegions(String value) {
        Set<Region> regions = newHashSet();

        for (String regionName : SPLITTER.split(value)) {
            try {
                if (!regions.add(Region.getRegion(Regions.fromName(regionName)))) {
                    throw new RuntimeException(format("Duplicate region name: %s", regionName));
                }
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException(format("Unknown region name: %s", regionName));
            }
        }

        return regions;
    }

    @Extension
    public static class Descriptor extends DockerHostProvider.Descriptor {
        @Override
        public String getDisplayName() {
            return "Amazon EC2";
        }

        public FormValidation doCheckFilterString(@QueryParameter String value) {
            value = nullToEmpty(value).trim();

            if (value.length() == 0) {
                return FormValidation.error("One or more filters are required");
            }

            try {
                parseFilters(value);
                return FormValidation.ok();
            } catch (Exception ex) {
                return FormValidation.error("Invalid instance filters: " + ex.getMessage());
            }
        }

        public FormValidation doCheckRegionString(@QueryParameter String value) {
            value = nullToEmpty(value).trim();

            if (value.length() == 0) {
                return FormValidation.error("One or more regions are required");
            }

            try {
                parseRegions(value);
                return FormValidation.ok();
            } catch (Exception ex) {
                return FormValidation.error("Invalid instance filters: " + ex.getMessage());
            }
        }

        public FormValidation doCheckInterfaceIndex(@QueryParameter int value) {
            return value >= 0
                    ? FormValidation.ok()
                    : FormValidation.error("Must be greater than or equal to 0");
        }
    }
}
