package net.dump247.jenkins.plugins.dockerbuild;

import com.amazonaws.services.autoscaling.AmazonAutoScaling;
import com.amazonaws.services.autoscaling.AmazonAutoScalingClient;
import com.amazonaws.services.autoscaling.model.AutoScalingInstanceDetails;
import com.amazonaws.services.autoscaling.model.DescribeAutoScalingInstancesRequest;
import com.amazonaws.services.autoscaling.model.DescribeAutoScalingInstancesResult;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.google.common.collect.ImmutableList;
import hudson.Extension;
import net.dump247.jenkins.plugins.dockerbuild.log.Logger;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.Collection;
import java.util.List;

import static com.google.common.base.Strings.nullToEmpty;
import static com.google.common.collect.Lists.newArrayList;

/**
 * Lists the instances in an AWS autoscaling group to run jobs on.
 */
public class AutoScalingDockerCloud extends DockerCloud {
    private static final Logger LOG = Logger.get(AutoScalingDockerCloud.class);

    public final String asgString;
    private transient List<String> _asgNames;

    private transient AmazonAutoScaling _amazonAutoScaling;
    private transient AmazonEC2 _amazonEC2;

    @DataBoundConstructor
    public AutoScalingDockerCloud(final String asgString, final int dockerPort, final String labelString, final int maxExecutors, final boolean tlsEnabled, final String credentialsId, final String directoryMappingsString) {
        super(dockerPort, labelString, maxExecutors, tlsEnabled, credentialsId, directoryMappingsString);

        this.asgString = asgString;

        readResolve();
    }

    @Override
    public Collection<DockerCloudHost> listHosts() {
        List<DockerCloudHost> hosts = newArrayList();
        String nextToken = null;

        do {
            LOG.debug("Listing {0} ASGs: [names={1}] [token={2}]", _asgNames.size(), _asgNames, nextToken);
            DescribeAutoScalingInstancesResult describeAsgResult = _amazonAutoScaling.describeAutoScalingInstances(new DescribeAutoScalingInstancesRequest()
                    .withInstanceIds(_asgNames)
                    .withNextToken(nextToken));

            for (AutoScalingInstanceDetails instanceDetails : describeAsgResult.getAutoScalingInstances()) {
                String instanceId = instanceDetails.getInstanceId();

                LOG.debug("Describing instance: [id={0}]", instanceId);
                DescribeInstancesResult describeInstancesResult = _amazonEC2.describeInstances(new DescribeInstancesRequest()
                        .withInstanceIds(instanceId));

                if (describeInstancesResult.getReservations().size() > 0) {
                    String privateIp = describeInstancesResult.getReservations().get(0).getInstances().get(0).getPrivateIpAddress();

                    LOG.debug("Found host: [ip={0}]", privateIp);
                    hosts.add(new DockerCloudHost(buildDockerClient(privateIp)));
                }
            }

            nextToken = describeAsgResult.getNextToken();
        } while (nextToken != null);

        return hosts;
    }

    @Override
    protected Object readResolve() {
        _asgNames = ImmutableList.copyOf(nullToEmpty(this.asgString).split("[,\\s]+"));
        _amazonAutoScaling = new AmazonAutoScalingClient();
        _amazonEC2 = new AmazonEC2Client();
        return super.readResolve();
    }

    @Extension
    public static final class Descriptor extends DockerCloud.Descriptor {
        @Override
        public String getDisplayName() {
            return "AWS AutoScaling Group";
        }
    }
}
