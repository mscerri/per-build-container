package com.atlassian.buildeng.ecs.scheduling;

import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ecs.model.ContainerInstance;
import com.atlassian.buildeng.ecs.Constants;
import com.atlassian.buildeng.ecs.exceptions.ECSException;
import com.google.common.annotations.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.lang3.tuple.Pair;

public class CyclingECSScheduler implements ECSScheduler, DisposableBean {
    static final Duration DEFAULT_STALE_PERIOD = Duration.ofDays(7); // One (1) week
    static final double DEFAULT_HIGH_WATERMARK = 0.9; // Scale when cluster is at 90% of maximum capacity

    private final Duration stalePeriod;
    private final double highWatermark;
    private final static Logger logger = LoggerFactory.getLogger(CyclingECSScheduler.class);
    private long lackingCPU = 0;
    private long lackingMemory = 0;
    private final Set<UUID> consideredRequestIdentifiers = new HashSet<>();
    @VisibleForTesting
    final ExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final BlockingQueue<Pair<SchedulingRequest, SchedulingCallback>> requests = new LinkedBlockingQueue<>();

    private final SchedulerBackend schedulerBackend;

    public CyclingECSScheduler(SchedulerBackend schedulerBackend) {
        stalePeriod = DEFAULT_STALE_PERIOD;
        highWatermark = DEFAULT_HIGH_WATERMARK;
        this.schedulerBackend = schedulerBackend;
        executor.submit(new EndlessPolling());
    }

    // Get the instance models of the given instance ARNs    
    List<DockerHost> getDockerHosts(List<ContainerInstance> containerInstances) throws ECSException {
        List<Instance> instances = schedulerBackend.getInstances(containerInstances);
        // Match up container instances and EC2 instances by instance id
        instances.sort((o1, o2) -> o1.getInstanceId().compareTo(o2.getInstanceId()));
        containerInstances.sort((o1, o2) -> o1.getEc2InstanceId().compareTo(o2.getEc2InstanceId()));

        int iSize = instances.size();
        int ciSize = containerInstances.size();

        List<DockerHost> dockerHosts = new ArrayList<>();

        if (iSize != ciSize) {
            logger.warn(String.format("Scheduler got different lengths for instances (%d) and container instances (%d)", iSize, ciSize));
        } else {
            for (int i = 0; i < ciSize; i++) {
                dockerHosts.add(new DockerHost(containerInstances.get(i), instances.get(i)));
            }
        }
        return dockerHosts;
    }

    // Select the best host to run a task with the given required resources out of a list of candidates
    // Is Nothing if there are no feasible hosts
    static Optional<DockerHost> selectHost(List<DockerHost> candidates, int requiredMemory, int requiredCpu) {
        return candidates.stream()
                .filter(dockerHost -> dockerHost.canRun(requiredMemory, requiredCpu))
                .sorted(DockerHost.compareByResources())
                .findFirst();
    }

    private Map<Boolean, List<DockerHost>> partitionFreshness(List<DockerHost> dockerHosts, Duration stalePeriod) {
        // Java pls
        return dockerHosts.stream()
                .collect(Collectors.partitioningBy(dockerHost -> dockerHost.ageMillis() < stalePeriod.toMillis()));
    }

    private Map<Boolean, List<DockerHost>> partitionFreshness(List<DockerHost> dockerHosts) {
        return partitionFreshness(dockerHosts, stalePeriod);
    }

    /**
     * Stream stale hosts not running any tasks
     */
    private List<DockerHost> unusedStaleInstances(List<DockerHost> staleHosts) {
        return staleHosts.stream()
                .filter(DockerHost::runningNothing)
                .collect(Collectors.toList());
    }

    private List<DockerHost> unusedFreshInstances(List<DockerHost> freshHosts, final Set<DockerHost> usedCandidates) {
        return freshHosts.stream()
                .filter(dockerHost -> !usedCandidates.contains(dockerHost))
                .filter(DockerHost::runningNothing)
                .filter(DockerHost::inSecondHalfOfBillingCycle)
                .collect(Collectors.toList());
    }

    // Scale up if capacity is near full
    static double percentageUtilized(List<DockerHost> freshHosts) {
        double clusterRegisteredCPU = freshHosts.stream().mapToInt(DockerHost::getRegisteredCpu).sum();
        double clusterRemainingCPU = freshHosts.stream().mapToInt(DockerHost::getRemainingCpu).sum();
        if (clusterRegisteredCPU == 0) {
            return 1;
        } else {
            return 1 - (clusterRemainingCPU / clusterRegisteredCPU);
        }
    }

    @Override
    public void schedule(SchedulingRequest request, SchedulingCallback callback) {
        requests.add(Pair.of(request, callback));
    }

    private void processRequests(Pair<SchedulingRequest, SchedulingCallback> pair) {
        if (pair == null) return;
        SchedulingRequest request = pair.getLeft();
        String cluster = request.getCluster();
        String asgName = request.getAsgName();

        final List<DockerHost> dockerHosts;
        try {
            //this can take time (network) and in the meantime other requests can accumulate.
            List<ContainerInstance> containerInstances = schedulerBackend.getClusterContainerInstances(cluster, asgName);
            dockerHosts = getDockerHosts(containerInstances);
        } catch (ECSException ex) {
            //mark all futures with exception.. and let the clients wait and retry..
            while (pair != null) {
                pair.getRight().handle(ex);
                pair = requests.poll();
            }
            logger.error("Cannot query cluster " + cluster + " containers", ex);
            return;
        }
        int currentSize = dockerHosts.size();
        final Map<Boolean, List<DockerHost>> partitionedHosts = partitionFreshness(dockerHosts);
        final List<DockerHost> freshHosts = partitionedHosts.get(true);
        final List<DockerHost> staleHosts = partitionedHosts.get(false);
        final Set<DockerHost> usedCandidates = new HashSet<>();
        boolean someDiscarded = false;
        while (pair != null) {
            try {
                logger.debug("Processing request for {}", pair);
                if (!cluster.equals(request.getCluster())) {
                    //we need to save current cluster.. new items arrived for different one.
                    logger.info("Skipped processing due to multiple clusters in queue");
                    throw new ECSException("Different cluster processed now.");
                }
                if (!asgName.equals(request.getAsgName())) {
                    //we need to save current cluster.. new items arrived for different one.
                    logger.info("Skipped processing due to multiple auto scaling groups in queue");
                    throw new ECSException("Different Auto Scaling Group Name processed now.");
                }
                Optional<DockerHost> candidate = selectHost(freshHosts, request.getMemory(), request.getCpu());
                if (candidate.isPresent()) {
                    DockerHost candidateHost = candidate.get();
                    SchedulingResult schedulingResult = schedulerBackend.schedule(candidateHost.getContainerInstanceArn(), request);
                    usedCandidates.add(candidateHost);
                    candidateHost.reduceAvailableCpuBy(request.getCpu());
                    candidateHost.reduceAvailableMemoryBy(request.getMemory());
                    pair.getRight().handle(schedulingResult);
                    lackingCPU = Math.max(0, lackingCPU - request.getCpu());
                    lackingMemory = Math.max(0, lackingMemory - request.getMemory());
                    // If we hit a stage where we're able to allocate a job + our deficit is less than a single agent
                    // Clear everything out, we're probably fine
                    if (lackingCPU < Constants.AGENT_CPU || lackingMemory < Constants.AGENT_MEMORY) {
                        consideredRequestIdentifiers.clear();
                        lackingCPU = 0;
                        lackingMemory = 0;
                    }
                } else {
                    // Note how much capacity we're lacking
                    // But don't double count the same request that comes through
                    if (consideredRequestIdentifiers.add(request.getIdentifier())) {
                        lackingCPU += request.getCpu();
                        lackingMemory += request.getMemory();
                    }
                    //scale up + down and set all other queued requests to null.
                    someDiscarded = true;
                    throw new ECSException("Capacity not available");
                }
            } catch (ECSException ex) {
                pair.getRight().handle(ex);
            }
            pair = requests.poll();
        }

        //see if we need to scale up or down..
        List<DockerHost> unusedStales = unusedStaleInstances(staleHosts);
        List<DockerHost> unusedFresh = unusedFreshInstances(freshHosts, usedCandidates);
        List<DockerHost> usedFresh = new ArrayList<>(freshHosts);
        usedFresh.removeAll(unusedFresh);
        int desiredScaleSize = currentSize;
        //calculate usage from used fresh instances only
        if (someDiscarded || percentageUtilized(usedFresh) >= highWatermark) {
            // cpu and memory requirements in instances
            long cpuRequirements = lackingCPU / Constants.INSTANCE_CPU;
            long memoryRequirements = lackingMemory / Constants.INSTANCE_MEMORY;
            logger.info("Scaling w.r.t. this much cpu " + lackingCPU);
            //if there are no unused fresh ones, scale up based on how many requests are pending, but always scale up
            //by at least one instance.
            long extraRequired = 1 + Math.max(cpuRequirements, memoryRequirements);

            desiredScaleSize += extraRequired;
        }
        List<String> toTerminate = Stream.concat(unusedStales.stream(), unusedFresh.stream())
                .map(DockerHost::getInstanceId)
                .collect(Collectors.toList());
        if (!toTerminate.isEmpty()) {
            desiredScaleSize = desiredScaleSize - toTerminate.size();
            //we are reducing the currentSize by the terminated list because that's
            //what the terminateInstances method should reduce it to.
            currentSize = currentSize - toTerminate.size();
            try {
                schedulerBackend.terminateInstances(toTerminate, asgName);
            } catch (ECSException ex) {
                logger.error("Terminating instances failed", ex);
            }
        }
        if (desiredScaleSize != currentSize) {
            try {
                schedulerBackend.scaleTo(desiredScaleSize, asgName);
            } catch (ECSException ex) {
                logger.error("Scaling of " + asgName + " failed", ex);
            }
        }
    }

    void shutdownExecutor() {
        executor.shutdown();
    }

    @Override
    public void destroy() throws Exception {
        shutdownExecutor();
    }

    private class EndlessPolling implements Runnable {

        public EndlessPolling() {
        }

        @Override
        public void run() {
            try {
                processRequests(requests.take());
            } catch (InterruptedException ex) {
                logger.info("Interrupted", ex);
            } catch (RuntimeException ex) {
                logger.error("Runtime Exception", ex);
            } catch (Throwable t) {
                logger.error("A very unexpected throwable", t);
            } finally {
                //try finally to guard against unexpected exceptions.
                executor.submit(this);
            }
        }

    }
}