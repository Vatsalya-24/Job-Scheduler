package com.jobScheduling.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobScheduling.job.controller.JobsController;
import com.jobScheduling.job.dto.JobsDTO;
import com.jobScheduling.job.exception.GlobalExceptionHandler;
import com.jobScheduling.job.exception.JobNotFoundException;
import com.jobScheduling.job.service.JobExecutionService;
import com.jobScheduling.job.service.JobLifecycleService;
import com.jobScheduling.job.service.JobsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(JobsController.class)
@Import(GlobalExceptionHandler.class)
class JobsControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private JobsService          jobsService;
    @MockBean private JobExecutionService  jobExecutionService;
    @MockBean private JobLifecycleService  lifecycleService;   // BUG 3 FIX — was missing

    @Test
    void createJob_returns201_withValidPayload() throws Exception {
        JobsDTO input = buildJobDTO("api-test-job", "0 0 * * * *", "ACTIVE");
        JobsDTO saved = buildJobDTO("api-test-job", "0 0 * * * *", "ACTIVE");
        saved.setId(1L);

        when(jobsService.createNewJob(any())).thenReturn(saved);

        mockMvc.perform(post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(input)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("api-test-job"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void createJob_returns400_whenNameMissing() throws Exception {
        JobsDTO input = new JobsDTO();
        input.setCronExpression("0 0 * * * *");
        input.setStatus("ACTIVE");
        input.setTarget("http://target");

        mockMvc.perform(post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(input)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid Request"));
    }

    @Test
    void createJob_returns400_whenCronInvalid() throws Exception {
        JobsDTO input = buildJobDTO("bad-cron-job", "NOT_A_CRON", "ACTIVE");

        mockMvc.perform(post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(input)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getJob_returns200_whenExists() throws Exception {
        JobsDTO dto = buildJobDTO("found-job", "0 * * * * *", "ACTIVE");
        dto.setId(5L);
        when(jobsService.getJobById(5L)).thenReturn(dto);

        mockMvc.perform(get("/api/v1/jobs/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.name").value("found-job"));
    }

    @Test
    void getJob_returns404_whenNotFound() throws Exception {
        when(jobsService.getJobById(999L))
                .thenThrow(new JobNotFoundException("Job not found: 999"));

        mockMvc.perform(get("/api/v1/jobs/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Job Not Found"));
    }

    @Test
    void disableJob_returns204() throws Exception {
        doNothing().when(jobsService).disableJob(1L);
        mockMvc.perform(delete("/api/v1/jobs/1"))
                .andExpect(status().isNoContent());
    }

    @Test
    void pause_returns200() throws Exception {
        JobsDTO dto = buildJobDTO("paused-job", "0 * * * * *", "PAUSED");
        dto.setId(1L);
        when(lifecycleService.pause(1L)).thenReturn(dto);

        mockMvc.perform(post("/api/v1/jobs/1/pause"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAUSED"));
    }

    @Test
    void resume_returns200() throws Exception {
        JobsDTO dto = buildJobDTO("active-job", "0 * * * * *", "ACTIVE");
        dto.setId(1L);
        when(lifecycleService.resume(1L)).thenReturn(dto);

        mockMvc.perform(post("/api/v1/jobs/1/resume"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    private JobsDTO buildJobDTO(String name, String cron, String status) {
        JobsDTO dto = new JobsDTO();
        dto.setName(name);
        dto.setCronExpression(cron);
        dto.setStatus(status);
        dto.setTarget("http://internal.service/run/" + name);
        return dto;
    }
}
