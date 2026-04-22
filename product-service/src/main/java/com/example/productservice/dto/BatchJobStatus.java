package com.example.productservice.dto;

public record BatchJobStatus(
        Long jobExecutionId,
        String status,
        long readCount,
        long writeCount,
        long skipCount,
        String startTime,
        String endTime,
        String exitDescription
) {}
