package com.jobScheduling.job.kafka;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * Event published when a new Job is created.
 * Used by downstream consumers (e.g., scheduler worker nodes) to
 * register the job for time-based firing.
 */
public class JobEvent {

    private final Long jobId;
    private final String name;
    private final String cronExpression;
    private final String target;
    private final String status;
    private final Instant eventTime;

    @JsonCreator
    public JobEvent(
            @JsonProperty("jobId") Long jobId,
            @JsonProperty("name") String name,
            @JsonProperty("cronExpression") String cronExpression,
            @JsonProperty("target") String target,
            @JsonProperty("status") String status,
            @JsonProperty("eventTime") Instant eventTime) {
        this.jobId = jobId;
        this.name = name;
        this.cronExpression = cronExpression;
        this.target = target;
        this.status = status;
        this.eventTime = eventTime;
    }

    public Long getJobId() { return jobId; }
    public String getName() { return name; }
    public String getCronExpression() { return cronExpression; }
    public String getTarget() { return target; }
    public String getStatus() { return status; }
    public Instant getEventTime() { return eventTime; }

    @Override
    public String toString() {
        return "JobEvent{jobId=" + jobId + ", name='" + name + "', status='" + status + "'}";
    }
}
