package com.example.productservice.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record ProductRequest(
        @NotBlank @Size(max = 64) String sku,
        @NotBlank @Size(max = 200) String name,
        @Size(max = 1000) String description,
        @NotNull @DecimalMin("0.00") BigDecimal price,
        @NotNull @Min(0) Integer stock
) {}
