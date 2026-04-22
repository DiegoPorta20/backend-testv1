package com.example.productservice.service;

import com.example.productservice.dto.ProductRequest;
import com.example.productservice.dto.ProductResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ProductService {
    Page<ProductResponse> findAll(String search, Pageable pageable);
    ProductResponse findById(Long id);
    ProductResponse create(ProductRequest req);
    ProductResponse update(Long id, ProductRequest req);
    void delete(Long id);
}
