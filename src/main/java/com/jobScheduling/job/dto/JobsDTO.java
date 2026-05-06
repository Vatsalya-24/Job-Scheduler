package com.jobScheduling.job.dto;

import com.jobScheduling.job.config.ValidCron;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.sql.Timestamp;

public class JobsDTO {

    private Long id;

    @NotNull(message = "Job name is required")
    @Size(max = 255, message = "Name must not exceed 255 characters")
    private String name;

    @NotNull(message = "Cron expression is required")
    @ValidCron
    private String cronExpression;

    @NotBlank(message = "Status is required")
    private String status;

    @NotBlank(message = "Target is required")
    private String target;

    private Timestamp createdAt;

    private Timestamp nextFireTime;

    public JobsDTO() {}

    public JobsDTO(Long id, String name, String cronExpression, String status,
                   String target, Timestamp createdAt, Timestamp nextFireTime) {
        this.id = id;
        this.name = name;
        this.cronExpression = cronExpression;
        this.status = status;
        this.target = target;
        this.createdAt = createdAt;
        this.nextFireTime = nextFireTime;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCronExpression() { return cronExpression; }
    public void setCronExpression(String cronExpression) { this.cronExpression = cronExpression; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }
    public Timestamp getCreatedAt() { return createdAt; }
    public void setCreatedAt(Timestamp createdAt) { this.createdAt = createdAt; }
    public Timestamp getNextFireTime() { return nextFireTime; }
    public void setNextFireTime(Timestamp nextFireTime) { this.nextFireTime = nextFireTime; }
}
