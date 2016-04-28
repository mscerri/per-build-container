package com.atlassian.buildeng.ecs.scheduling;


import java.util.UUID;

public class SchedulingRequest {
    private final String cluster;
    private final String asgName;
    private final UUID identifier;
    private final String resultId;
    private final Integer revision;
    private final int cpu;
    private final int memory;

    public SchedulingRequest(String cluster, String asgName, UUID identifier, String resultId, Integer revision, int cpu, int memory) {
        this.cluster = cluster;
        this.asgName = asgName;
        this.identifier = identifier;
        this.resultId = resultId;
        this.revision = revision;
        this.cpu = cpu;
        this.memory = memory;
    }

    public String getCluster() {
        return cluster;
    }

    public String getAsgName() {
        return asgName;
    }

    public UUID getIdentifier() {
        return identifier;
    }

    public String getResultId() {
        return resultId;
    }

    public Integer getRevision() {
        return revision;
    }

    public int getCpu() {
        return cpu;
    }

    public int getMemory() {
        return memory;
    }

}
