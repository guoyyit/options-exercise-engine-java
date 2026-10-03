package com.github.guoyyit.optionsexerciseenginejava.config;

import com.github.guoyyit.optionsexerciseenginejava.domain.AssignmentStrategy;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;

@ConfigurationProperties(prefix = "exercise")
public class ExerciseProperties {
    private String stateFile = "data/state.json";
    private String dataDirectory = "data";
    private BigDecimal intrinsicThreshold = BigDecimal.ZERO;
    private AssignmentStrategy assignmentStrategy = AssignmentStrategy.PRO_RATA;
    private long randomSeed = 20261003L;
    private int batchSize = 100;
    private int concurrency = 2;
    private Duration processingTimeout = Duration.ofMinutes(5);
    private int cutoffHour = 16;
    private ZoneId zoneId = ZoneId.systemDefault();

    public String getStateFile() {
        return stateFile;
    }

    public void setStateFile(String stateFile) {
        this.stateFile = stateFile;
    }

    public String getDataDirectory() {
        return dataDirectory;
    }

    public void setDataDirectory(String dataDirectory) {
        this.dataDirectory = dataDirectory;
    }

    public BigDecimal getIntrinsicThreshold() {
        return intrinsicThreshold;
    }

    public void setIntrinsicThreshold(BigDecimal intrinsicThreshold) {
        this.intrinsicThreshold = intrinsicThreshold;
    }

    public AssignmentStrategy getAssignmentStrategy() {
        return assignmentStrategy;
    }

    public void setAssignmentStrategy(AssignmentStrategy assignmentStrategy) {
        this.assignmentStrategy = assignmentStrategy;
    }

    public long getRandomSeed() {
        return randomSeed;
    }

    public void setRandomSeed(long randomSeed) {
        this.randomSeed = randomSeed;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = Math.max(1, batchSize);
    }

    public int getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(int concurrency) {
        this.concurrency = Math.max(1, concurrency);
    }

    public Duration getProcessingTimeout() {
        return processingTimeout;
    }

    public void setProcessingTimeout(Duration processingTimeout) {
        this.processingTimeout = processingTimeout;
    }

    public int getCutoffHour() {
        return cutoffHour;
    }

    public void setCutoffHour(int cutoffHour) {
        this.cutoffHour = cutoffHour;
    }

    public ZoneId getZoneId() {
        return zoneId;
    }

    public void setZoneId(ZoneId zoneId) {
        this.zoneId = zoneId;
    }
}
