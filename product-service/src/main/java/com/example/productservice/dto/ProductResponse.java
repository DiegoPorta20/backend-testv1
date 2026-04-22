package com.example.productservice.dto;

import com.example.productservice.domain.Product;

import java.math.BigDecimal;
import java.time.Instant;

public record ProductResponse(
        Long id,
        String sku,
        String name,
        String description,
        BigDecimal price,
        Integer stock,
        Instant createdAt,
        Instant updatedAt
) {
    public static ProductResponse from(Product p) {
        return new ProductResponse(
                p.getId(), p.getSku(), p.getName(), p.getDescription(),
                p.getPrice(), p.getStock(), p.getCreatedAt(), p.getUpdatedAt()
        );
    }
}
