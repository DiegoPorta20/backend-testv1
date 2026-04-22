package com.example.productservice.batch;

import com.example.productservice.domain.Product;
import com.example.productservice.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemProcessor;

import java.math.BigDecimal;

public class ProductItemProcessor implements ItemProcessor<ProductCsvRow, Product> {

    private static final Logger log = LoggerFactory.getLogger(ProductItemProcessor.class);

    private final ProductRepository productRepository;

    public ProductItemProcessor(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Override
    public Product process(ProductCsvRow row) {
        if (row.getSku() == null || row.getSku().isBlank()) {
            log.warn("Skipping row without SKU: {}", row);
            return null;
        }
        if (productRepository.existsBySku(row.getSku().trim())) {
            log.info("Skipping existing SKU: {}", row.getSku());
            return null;
        }
        try {
            return Product.builder()
                    .sku(row.getSku().trim())
                    .name(row.getName() == null ? "" : row.getName().trim())
                    .description(row.getDescription() == null ? null : row.getDescription().trim())
                    .price(new BigDecimal(row.getPrice().trim()))
                    .stock(Integer.parseInt(row.getStock().trim()))
                    .build();
        } catch (NumberFormatException | NullPointerException ex) {
            log.warn("Skipping invalid row {}: {}", row, ex.getMessage());
            return null;
        }
    }
}
