package com.ecommerce.backend.batch;

import com.ecommerce.backend.domain.Order;
import com.ecommerce.backend.service.CandidateGeneratorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;


@Slf4j
@Configuration
@RequiredArgsConstructor
public class TaskletBatch {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final MongoTemplate mongoTemplate;
    private final CandidateGeneratorService candidateGeneratorService;

    @Bean
    public Job processOrdersJob() {
        return new JobBuilder("processOrdersJob", jobRepository)
                .start(processOrdersStep())
                .build();
    }

    @Bean
    public Step processOrdersStep() {
        return new StepBuilder("processOrdersStep", jobRepository)
                .tasklet(orderTasklet(), transactionManager)
                .build();
    }

    @Bean
    public Tasklet orderTasklet() {
        return (contribution, chunkContext) -> {

            candidateGeneratorService.splitData();

            return RepeatStatus.FINISHED;
        };
    }
}