package com.example.productservice.service;

import com.example.productservice.dto.BatchJobStatus;
import org.springframework.web.multipart.MultipartFile;

public interface BatchJobService {
    BatchJobStatus importProductsFromCsv(MultipartFile file);
    BatchJobStatus getStatus(Long jobExecutionId);
}
