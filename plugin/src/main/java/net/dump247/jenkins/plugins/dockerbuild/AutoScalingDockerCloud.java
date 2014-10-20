package net.dump247.jenkins.plugins.dockerbuild;

import com.amazonaws.services.autoscaling.AmazonAutoScaling;
import com.amazonaws.services.autoscaling.AmazonAutoScalingClient;
import com.amazonaws.services.autoscaling.model.AutoScalingGroup;
import com.amazonaws.services.autoscaling.model.DescribeAutoScalingGroupsRequest;
import com.amazonaws.services.autoscaling.model.DescribeAutoScalingGroupsResult;
import com.amazonaws.services.autoscaling.model.Instance;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import hudson.Extension;
import hudson.util.FormValidation;
import net.dump247.jenkins.plugins.dockerbuild.log.Logger;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import java.util.Collection;
import java.util.List;
import java.util.Map;

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
    public AutoScalingDockerCloud(String name, final String asgString, final int dockerPort, final String labelString, final int maxExecutors, final boolean tlsEnabled, final String credentialsId, final String directoryMappingsString, boolean allowCustomImages) {
        super(name, dockerPort, labelString, maxExecutors, tlsEnabled, credentialsId, directoryMappingsString, allowCustomImages);

        this.asgString = asgString;

        readResolve();
    }

    @Override
    public Collection<DockerCloudHost> listHosts() {
        List<DockerCloudHost> hosts = newArrayList();
        String nextToken = null;

        do {
            LOG.debug("Listing {0} ASGs: [names={1}] [token={2}]", _asgNames.size(), _asgNames, nextToken);
            DescribeAutoScalingGroupsResult describeAsgResult = _amazonAutoScaling.describeAutoScalingGroups(
                    new DescribeAutoScalingGroupsRequest()
                            .withAutoScalingGroupNames(_asgNames));

            for (AutoScalingGroup autoScalingGroup : describeAsgResult.getAutoScalingGroups()) {
                for (Instance autoscalingInstance : autoScalingGroup.getInstances()) {
                    String instanceId = autoscalingInstance.getInstanceId();

                    LOG.debug("Describing instance: [id={0}]", instanceId);
                    DescribeInstancesResult describeInstancesResult = _amazonEC2.describeInstances(new DescribeInstancesRequest()
                            .withInstanceIds(instanceId));

                    if (describeInstancesResult.getReservations().size() > 0) {
                        String privateIp = describeInstancesResult.getReservations().get(0).getInstances().get(0).getPrivateIpAddress();

                        LOG.debug("Found host: [ip={0}]", privateIp);
                        hosts.add(new DockerCloudHost(buildDockerClient(privateIp)));
                    }
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
            return "Docker Amazon AutoScaling Group";
        }

        public FormValidation doCheckAsgString(@QueryParameter String value) {
            return nullToEmpty(value).trim().length() == 0
                    ? FormValidation.error("At least one AutoScaling group is required")
                    : FormValidation.ok();
        }
    }
}
