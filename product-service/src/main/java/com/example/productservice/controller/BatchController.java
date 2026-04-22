package com.example.productservice.controller;

import com.example.productservice.dto.BatchJobStatus;
import com.example.productservice.service.BatchJobService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/products/batch")
public class BatchController {

    private final BatchJobService batchJobService;

    public BatchController(BatchJobService batchJobService) {
        this.batchJobService = batchJobService;
    }

    @PostMapping(value = "/import", consumes = "multipart/form-data")
    public ResponseEntity<BatchJobStatus> importCsv(@RequestParam("file") MultipartFile file) {
        return ResponseEntity.accepted().body(batchJobService.importProductsFromCsv(file));
    }

    @GetMapping("/{jobExecutionId}")
    public ResponseEntity<BatchJobStatus> status(@PathVariable Long jobExecutionId) {
        return ResponseEntity.ok(batchJobService.getStatus(jobExecutionId));
    }
}
