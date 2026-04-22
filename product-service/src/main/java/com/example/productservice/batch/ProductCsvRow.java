package com.example.productservice.batch;

import lombok.Data;

@Data
public class ProductCsvRow {
    private String sku;
    private String name;
    private String description;
    private String price;
    private String stock;
}
