/*
 * Copyright 2016 Atlassian.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.atlassian.buildeng.ecs.scheduling;

import com.amazonaws.services.autoscaling.AmazonAutoScalingClient;
import com.amazonaws.services.autoscaling.model.AutoScalingGroup;
import com.amazonaws.services.autoscaling.model.DescribeAutoScalingGroupsRequest;
import com.amazonaws.services.autoscaling.model.DetachInstancesRequest;
import com.amazonaws.services.autoscaling.model.DetachInstancesResult;
import com.amazonaws.services.autoscaling.model.SetDesiredCapacityRequest;
import com.amazonaws.services.autoscaling.model.SuspendProcessesRequest;
import com.amazonaws.services.autoscaling.model.SuspendProcessesResult;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.TerminateInstancesRequest;
import com.amazonaws.services.ec2.model.TerminateInstancesResult;
import com.amazonaws.services.ecs.AmazonECSClient;
import com.amazonaws.services.ecs.model.ContainerInstance;
import com.amazonaws.services.ecs.model.ContainerOverride;
import com.amazonaws.services.ecs.model.DescribeContainerInstancesRequest;
import com.amazonaws.services.ecs.model.DescribeTasksRequest;
import com.amazonaws.services.ecs.model.DescribeTasksResult;
import com.amazonaws.services.ecs.model.KeyValuePair;
import com.amazonaws.services.ecs.model.ListContainerInstancesRequest;
import com.amazonaws.services.ecs.model.ListContainerInstancesResult;
import com.amazonaws.services.ecs.model.StartTaskRequest;
import com.amazonaws.services.ecs.model.StartTaskResult;
import com.amazonaws.services.ecs.model.Task;
import com.amazonaws.services.ecs.model.TaskOverride;
import com.atlassian.buildeng.ecs.Constants;
import com.atlassian.buildeng.ecs.GlobalConfiguration;
import com.atlassian.buildeng.ecs.exceptions.ECSException;
import com.atlassian.buildeng.spi.isolated.docker.Configuration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * class encapsulating all AWS interaction for the CyclingECSScheduler
 */
public class AWSSchedulerBackend implements SchedulerBackend {
    private final static Logger logger = LoggerFactory.getLogger(AWSSchedulerBackend.class);

    @Override
    public List<ContainerInstance> getClusterContainerInstances(String cluster) throws ECSException {
        try {
            AmazonECSClient ecsClient = new AmazonECSClient();
            ListContainerInstancesRequest listReq = new ListContainerInstancesRequest()
                    .withCluster(cluster);

            // Get containerInstanceArns
            boolean finished = false;
            Collection<String> containerInstanceArns = new ArrayList<>();
            while (!finished) {
                ListContainerInstancesResult listContainerInstancesResult = ecsClient.listContainerInstances(listReq);
                containerInstanceArns.addAll(listContainerInstancesResult.getContainerInstanceArns());
                String nextToken = listContainerInstancesResult.getNextToken();
                if (nextToken == null) {
                    finished = true;
                } else {
                    listReq.setNextToken(nextToken);
                }
            }

            if (containerInstanceArns.isEmpty()) {
                return Collections.emptyList();
            } else {
                DescribeContainerInstancesRequest describeReq = new DescribeContainerInstancesRequest()
                        .withCluster(cluster)
                        .withContainerInstances(containerInstanceArns);
                return ecsClient.describeContainerInstances(describeReq).getContainerInstances().stream()
                        .collect(Collectors.toList());
            }
        } catch (Exception ex) {
            throw new ECSException(ex);
        }
    }

    @Override
    public List<Instance> getInstances(Collection<String> instanceIds) throws ECSException {
        List<Instance> instances = new ArrayList<>();
        if (!instanceIds.isEmpty()) try {
            AmazonEC2Client ec2Client = new AmazonEC2Client();
            DescribeInstancesRequest req = new DescribeInstancesRequest()
                    .withInstanceIds(instanceIds);
            boolean finished = false;

            while (!finished) {
                DescribeInstancesResult describeInstancesResult = ec2Client.describeInstances(req);
                describeInstancesResult.getReservations().forEach(reservation -> instances.addAll(reservation.getInstances()));
                String nextToken = describeInstancesResult.getNextToken();
                if (nextToken == null) {
                    finished = true;
                } else {
                    req.setNextToken(nextToken);
                }
            }
        } catch (Exception ex) {
            throw new ECSException(ex);
        }
        return instances.stream().distinct().collect(Collectors.toList());
    }

    @Override
    public void scaleTo(int desiredCapacity, String autoScalingGroup) throws ECSException {
        logger.info("Scaling to capacity: {} in ASG: {}", desiredCapacity, autoScalingGroup);
        try {
            AmazonAutoScalingClient asClient = new AmazonAutoScalingClient();
            asClient.setDesiredCapacity(new SetDesiredCapacityRequest()
                    .withDesiredCapacity(desiredCapacity)
                    .withAutoScalingGroupName(autoScalingGroup)
            );
        } catch (Exception e) {
            throw new ECSException(e);
        }
    }

    @Override
    public void terminateInstances(List<DockerHost> hosts, String asgName, boolean decrementSize) throws ECSException {
        try {
            logger.info("Detaching and terminating unused and stale instances: {}", hosts);
            final List<String> asgInstances = hosts.stream().filter(DockerHost::isPresentInASG).map(DockerHost::getInstanceId).collect(Collectors.toList());
            if (!asgInstances.isEmpty()) {
                AmazonAutoScalingClient asClient = new AmazonAutoScalingClient();
                DetachInstancesResult result = 
                      asClient.detachInstances(new DetachInstancesRequest()
                            .withAutoScalingGroupName(asgName)
                              //only detach instances that are actually in the ASG group
                            .withInstanceIds(asgInstances)
                            .withShouldDecrementDesiredCapacity(decrementSize));
                logger.info("Result of detachment: {}", result);
            }
            AmazonEC2Client ec2Client = new AmazonEC2Client();
            TerminateInstancesResult ec2Result = ec2Client.terminateInstances(
                    new TerminateInstancesRequest(hosts.stream().map(DockerHost::getInstanceId).collect(Collectors.toList())));
            logger.info("Result of instance termination: {}" + ec2Result);
        } catch (Exception e) {
            throw new ECSException(e);
        }
    }

    @Override
    public SchedulingResult schedule(String containerArn, String cluster, SchedulingRequest request, String taskDefinition) throws ECSException {
        try {
            AmazonECSClient ecsClient = new AmazonECSClient();
            TaskOverride overrides = new TaskOverride();
            ContainerOverride buildResultOverride = new ContainerOverride()
                .withEnvironment(new KeyValuePair().withName(Constants.ENV_VAR_RESULT_ID).withValue(request.getResultId()))
                .withEnvironment(new KeyValuePair().withName(Constants.ECS_CONTAINER_INSTANCE_ARN_KEY).withValue(containerArn))
                .withName(Constants.AGENT_CONTAINER_NAME);
            overrides.withContainerOverrides(buildResultOverride);
            request.getConfiguration().getExtraContainers().forEach((Configuration.ExtraContainer t) -> {
                List<String> adjustedCommands = adjustCommands(t);
                if (!adjustedCommands.isEmpty() || !t.getEnvVariables().isEmpty()) {
                    ContainerOverride ride = new ContainerOverride().withName(t.getName());
                    adjustedCommands.forEach((String t1) -> {
                        ride.withCommand(t1);
                    });
                    t.getEnvVariables().forEach((Configuration.EnvVariable t1) -> {
                        ride.withEnvironment(new KeyValuePair().withName(t1.getName()).withValue(t1.getValue()));
                    });
                    overrides.withContainerOverrides(ride);
                }
            });
            StartTaskResult startTaskResult = ecsClient.startTask(new StartTaskRequest()
                    .withCluster(cluster)
                    .withContainerInstances(containerArn)
                    .withTaskDefinition(taskDefinition + ":" + request.getRevision())
                    .withOverrides(overrides)
            );
            return new SchedulingResult(startTaskResult, containerArn);
        } catch (Exception e) {
            throw new ECSException(e);
        }
    }

    /**
     * 
     * @param autoScalingGroup name
     * @return described autoscaling group
     * @throws ECSException 
     */
    @Override
    public AutoScalingGroup describeAutoScalingGroup(String autoScalingGroup) throws ECSException {
        try {
            AmazonAutoScalingClient asgClient = new AmazonAutoScalingClient();
            DescribeAutoScalingGroupsRequest asgReq = new DescribeAutoScalingGroupsRequest()
                    .withAutoScalingGroupNames(autoScalingGroup);
            List<AutoScalingGroup> groups = asgClient.describeAutoScalingGroups(asgReq).getAutoScalingGroups();
            if (groups.size() > 1) {
                throw new ECSException("More than one group by name:" + autoScalingGroup);
            }
            if (groups.isEmpty()) {
                throw new ECSException("No auto scaling group with name:" + autoScalingGroup);
            }
            return groups.get(0);
        } catch (Exception ex) {
            if (ex instanceof ECSException) {
                throw ex;
            } else {
                throw new ECSException(ex);
            }
        }
    }
    

    @Override
    public Collection<Task> checkTasks(String cluster, Collection<String> taskArns) throws ECSException {
        AmazonECSClient ecsClient = new AmazonECSClient();
        try {
            final List<Task> toRet = new ArrayList<>();
            DescribeTasksResult res = ecsClient.describeTasks(new DescribeTasksRequest().withCluster(cluster).withTasks(taskArns));
            res.getTasks().forEach((Task t) -> {
                toRet.add(t);
            });
            if (!res.getFailures().isEmpty()) {
                if (toRet.isEmpty()) {
                    throw new ECSException(Arrays.toString(res.getFailures().toArray()));
                } else {
                    logger.info("Error on retrieving tasks: {}",Arrays.toString(res.getFailures().toArray()));
                }
            }
            return toRet;
        } catch (Exception ex) {
            if (ex instanceof ECSException) {
                throw ex;
            } else {
                throw new ECSException(ex);
            }
        }
        
    }

    /**
     * adjust the list of commands if required, eg. in case of storage-driver switch for
     * docker in docker images.
     * @param t
     * @return
     */
    static List<String> adjustCommands(Configuration.ExtraContainer t) {
        if (GlobalConfiguration.isDockerInDockerImage(t.getImage())) {
            List<String> cmds = new ArrayList<>(t.getCommands());
            Iterator<String> it = cmds.iterator();
            while (it.hasNext()) {
                String s = it.next().trim();
                if (s.startsWith("-s") || s.startsWith("--storage-driver") || s.startsWith("--storage-opt")) {
                    it.remove();
                    if (!s.contains("=") && it.hasNext()) {
                        it.next();
                        it.remove();
                    }
                }
            }
            cmds.add("--storage-driver=" + Constants.storage_driver);
            return cmds;
        }
        return t.getCommands();
    }

    @Override
    public void suspendProcess(String autoScalingGroupName, String processName) throws ECSException {
        try {
            AmazonAutoScalingClient asgClient = new AmazonAutoScalingClient();
            SuspendProcessesRequest req = new SuspendProcessesRequest()
                    .withAutoScalingGroupName(autoScalingGroupName)
                    .withScalingProcesses(processName);
            asgClient.suspendProcesses(req);
        } catch (Exception ex) {
            throw new ECSException(ex);
        }
    }
}
