package com.ecommerce.backend.batch;

import com.ecommerce.backend.domain.Order;
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
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class TaskletBatch {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final MongoTemplate mongoTemplate;

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
            log.info("시작Starting to read all Orders from MongoDB...");

            List<Order> orders = mongoTemplate.findAll(Order.class);

            if (orders.isEmpty()) {
                log.warn("No Orders found in MongoDB.");
            } else {
                for (Order order : orders) {
                    log.info("Processing Order with ID: {}", order.getId());
                    // 예: 상태값 변경
                    // order.setStatus("PROCESSED");

                    log.info("ㅇㅇㅇㅇㅇㅇㅇㅇㅇ: {}", order.getProducts());
                    // 실제 저장 로직
                    // mongoTemplate.save(order);
                }
            }

            log.info("Finished processing Orders.");
            return RepeatStatus.FINISHED;
        };
    }
}