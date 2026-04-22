package com.example.productservice.service.impl;

import com.example.productservice.batch.BatchConfig;
import com.example.productservice.dto.BatchJobStatus;
import com.example.productservice.exception.BusinessException;
import com.example.productservice.service.BatchJobService;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Service
public class BatchJobServiceImpl implements BatchJobService {

    private final JobLauncher jobLauncher;
    private final Job importProductsJob;
    private final JobExplorer jobExplorer;
    private final Path uploadDir;

    public BatchJobServiceImpl(
            JobLauncher jobLauncher,
            Job importProductsJob,
            JobExplorer jobExplorer,
            @Value("${app.batch.upload-dir}") String uploadDir
    ) {
        this.jobLauncher = jobLauncher;
        this.importProductsJob = importProductsJob;
        this.jobExplorer = jobExplorer;
        this.uploadDir = Paths.get(uploadDir);
    }

    @Override
    public BatchJobStatus importProductsFromCsv(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "CSV file is empty");
        }
        String original = file.getOriginalFilename() == null ? "upload.csv" : file.getOriginalFilename();
        if (!original.toLowerCase().endsWith(".csv")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Only .csv files are accepted");
        }
        try {
            Files.createDirectories(uploadDir);
            Path target = uploadDir.resolve(UUID.randomUUID() + "-" + Paths.get(original).getFileName().toString());
            file.transferTo(target.toFile());

            JobParameters params = new JobParametersBuilder()
                    .addString(BatchConfig.PARAM_FILE_PATH, target.toAbsolutePath().toString())
                    .addLong("ts", System.currentTimeMillis())
                    .toJobParameters();

            JobExecution exec = jobLauncher.run(importProductsJob, params);
            return toStatus(exec);
        } catch (IOException ex) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to store upload: " + ex.getMessage());
        } catch (Exception ex) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "Batch job launch failed: " + ex.getMessage());
        }
    }

    @Override
    public BatchJobStatus getStatus(Long jobExecutionId) {
        JobExecution exec = jobExplorer.getJobExecution(jobExecutionId);
        if (exec == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "Job execution not found");
        }
        return toStatus(exec);
    }

    private BatchJobStatus toStatus(JobExecution exec) {
        long read = exec.getStepExecutions().stream().mapToLong(se -> se.getReadCount()).sum();
        long write = exec.getStepExecutions().stream().mapToLong(se -> se.getWriteCount()).sum();
        long skip = exec.getStepExecutions().stream().mapToLong(se -> se.getSkipCount()).sum();
        DateTimeFormatter fmt = DateTimeFormatter.ISO_INSTANT;
        String start = exec.getStartTime() == null ? null : exec.getStartTime().atZone(ZoneId.systemDefault()).toInstant().toString();
        String end = exec.getEndTime() == null ? null : exec.getEndTime().atZone(ZoneId.systemDefault()).toInstant().toString();
        return new BatchJobStatus(
                exec.getId(),
                exec.getStatus().name(),
                read, write, skip,
                start, end,
                exec.getExitStatus().getExitDescription()
        );
    }
}
