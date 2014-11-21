package net.dump247.jenkins.plugins.dockerbuild;

import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.Filter;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.Reservation;
import hudson.Extension;
import hudson.util.FormValidation;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import java.util.Collection;
import java.util.List;
import java.util.logging.Logger;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.nullToEmpty;
import static com.google.common.collect.Lists.newArrayList;
import static java.lang.String.format;
import static net.dump247.jenkins.plugins.dockerbuild.ConfigUtil.splitConfigLines;

/**
 * Cloud that searches for amazon EC2 instances to run jobs on.
 * <p/>
 * Given a filter, the cloud searches for instances that match the filter.
 */
public class AmazonDockerCloud extends DockerCloud {
    private static final Logger LOG = Logger.getLogger(AmazonDockerCloud.class.getName());

    public final String filterString;
    private transient List<Filter> _filters;

    private transient AmazonEC2 _amazonEC2;

    @DataBoundConstructor
    public AmazonDockerCloud(String name, String filterString, int dockerPort, String labelString,
                             int maxExecutors, boolean tlsEnabled, String credentialsId,
                             String directoryMappingsString, boolean allowCustomImages, String slaveJarPath) {
        super(name, dockerPort, labelString, maxExecutors, tlsEnabled, credentialsId, directoryMappingsString, allowCustomImages, slaveJarPath);
        this.filterString = filterString;
        readResolve();
    }

    @Override
    public Collection<DockerCloudHost> listHosts() {
        List<DockerCloudHost> hosts = newArrayList();
        DescribeInstancesRequest request = new DescribeInstancesRequest().withFilters(_filters);
        String nextToken = null;

        do {
            DescribeInstancesResult result = _amazonEC2.describeInstances(request.withNextToken(nextToken));

            for (Reservation reservation : result.getReservations()) {
                for (Instance instance : reservation.getInstances()) {
                    String privateIp = instance.getPrivateIpAddress();
                    LOG.fine(format("Found host: [instance=%s] [ip=%s]", instance.getInstanceId(), privateIp));
                    hosts.add(buildDockerClient(privateIp));
                }
            }

            nextToken = result.getNextToken();
        } while (nextToken != null);

        return hosts;
    }

    @Override
    protected Object readResolve() {
        _filters = parseFilterString(nullToEmpty(filterString));
        _amazonEC2 = new AmazonEC2Client();
        return super.readResolve();
    }

    private static List<Filter> parseFilterString(String filterString) {
        List<Filter> filters = newArrayList();

        for (ConfigUtil.ConfigLine filterLine : splitConfigLines(filterString)) {
            String[] nameValues = filterLine.value.split("=", 2);
            checkArgument(nameValues.length == 2, format("Filter must be 'name=value[,value,value] (line %d): %s", filterLine.lineNum, filterLine));

            String filterName = nameValues[0].trim();
            checkArgument(filterName.length() > 0, format("Filter must be 'name=value[,value,value] (line %d): %s", filterLine.lineNum, filterLine));

            filters.add(new Filter()
                    .withName(filterName)
                    .withValues(nameValues[1].split(",")));
        }

        return filters;
    }

    @Extension
    public static final class Descriptor extends DockerCloud.Descriptor {
        @Override
        public String getDisplayName() {
            return "Docker Amazon EC2";
        }

        public FormValidation doCheckFilterString(@QueryParameter String value) {
            value = nullToEmpty(value).trim();

            if (value.length() == 0) {
                return FormValidation.error("One or more filters are required");
            }

            try {
                parseFilterString(value);
                return FormValidation.ok();
            } catch (Exception ex) {
                return FormValidation.error("Invalid instance filters: " + ex.getMessage());
            }
        }
    }
}
