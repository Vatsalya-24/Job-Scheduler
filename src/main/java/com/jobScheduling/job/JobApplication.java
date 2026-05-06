package com.jobScheduling.job;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
//@EnableSpringDataWebSupport(
//    pageSerializationMode = EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO
//)
public class JobApplication {
    public static void main(String[] args) {
        System.setProperty("spring.threads.virtual.enabled", "true");
        SpringApplication.run(JobApplication.class, args);
    }
}
