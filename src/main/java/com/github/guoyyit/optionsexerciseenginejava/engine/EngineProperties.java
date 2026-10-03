package com.github.guoyyit.optionsexerciseenginejava.engine;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "exercise.engine")
public class EngineProperties {

    private long exerciseThreshold = 0L;
    private int batchSize = 100;
    private int concurrency = 1;
    private long operationTimeoutMs = 0L;
    private String assignmentStrategy = "proportional";
    private long randomSeed = 0L;
    private long interventionWindowMs = 0L;
    private String stateDir = "target/engine-state";

    public long getExerciseThreshold() {
        return exerciseThreshold;
    }

    public void setExerciseThreshold(long exerciseThreshold) {
        this.exerciseThreshold = exerciseThreshold;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(int concurrency) {
        this.concurrency = concurrency;
    }

    public long getOperationTimeoutMs() {
        return operationTimeoutMs;
    }

    public void setOperationTimeoutMs(long operationTimeoutMs) {
        this.operationTimeoutMs = operationTimeoutMs;
    }

    public String getAssignmentStrategy() {
        return assignmentStrategy;
    }

    public void setAssignmentStrategy(String assignmentStrategy) {
        this.assignmentStrategy = assignmentStrategy;
    }

    public long getRandomSeed() {
        return randomSeed;
    }

    public void setRandomSeed(long randomSeed) {
        this.randomSeed = randomSeed;
    }

    public long getInterventionWindowMs() {
        return interventionWindowMs;
    }

    public void setInterventionWindowMs(long interventionWindowMs) {
        this.interventionWindowMs = interventionWindowMs;
    }

    public String getStateDir() {
        return stateDir;
    }

    public void setStateDir(String stateDir) {
        this.stateDir = stateDir;
    }
}
