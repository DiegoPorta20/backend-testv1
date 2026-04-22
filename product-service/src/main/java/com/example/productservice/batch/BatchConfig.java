package com.example.productservice.batch;

import com.example.productservice.domain.Product;
import com.example.productservice.repository.ProductRepository;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.database.JpaItemWriter;
import org.springframework.batch.item.database.builder.JpaItemWriterBuilder;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class BatchConfig {

    public static final String JOB_NAME = "importProductsJob";
    public static final String STEP_NAME = "importPr oductsStep";
    public static final String PARAM_FILE_PATH = "filePath";

    @Bean
    @org.springframework.batch.core.configuration.annotation.StepScope
    public FlatFileItemReader<ProductCsvRow> reader(@Value("#{jobParameters['" + PARAM_FILE_PATH + "']}") String filePath) {
        return new FlatFileItemReaderBuilder<ProductCsvRow>()
                .name("productItemReader")
                .resource(new FileSystemResource(filePath))
                .linesToSkip(1)
                .delimited()
                .delimiter(",")
                .names("sku", "name", "description", "price", "stock")
                .targetType(ProductCsvRow.class)
                .build();
    }

    @Bean
    public ProductItemProcessor processor(ProductRepository productRepository) {
        return new ProductItemProcessor(productRepository);
    }

    @Bean
    public JpaItemWriter<Product> writer(EntityManagerFactory emf) {
        return new JpaItemWriterBuilder<Product>()
                .entityManagerFactory(emf)
                .build();
    }

    @Bean
    public Step importProductsStep(
            JobRepository jobRepository,
            PlatformTransactionManager txManager,
            FlatFileItemReader<ProductCsvRow> reader,
            ProductItemProcessor processor,
            JpaItemWriter<Product> writer
    ) {
        return new StepBuilder(STEP_NAME, jobRepository)
                .<ProductCsvRow, Product>chunk(100, txManager)
                .reader(reader)
                .processor(processor)
                .writer(writer)
                .faultTolerant()
                .skipLimit(50)
                .skip(RuntimeException.class)
                .build();
    }

    @Bean
    public Job importProductsJob(JobRepository jobRepository, Step importProductsStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(importProductsStep)
                .build();
    }
}
