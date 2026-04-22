package com.example.productservice.service.impl;

import com.example.productservice.domain.Product;
import com.example.productservice.dto.ProductRequest;
import com.example.productservice.dto.ProductResponse;
import com.example.productservice.exception.BusinessException;
import com.example.productservice.repository.ProductRepository;
import com.example.productservice.service.ProductService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;

    public ProductServiceImpl(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProductResponse> findAll(String search, Pageable pageable) {
        Page<Product> page = StringUtils.hasText(search)
                ? productRepository.findByNameContainingIgnoreCase(search, pageable)
                : productRepository.findAll(pageable);
        return page.map(ProductResponse::from);
    }

    @Override
    @Transactional(readOnly = true)
    public ProductResponse findById(Long id) {
        return productRepository.findById(id)
                .map(ProductResponse::from)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "Product not found"));
    }

    @Override
    @Transactional
    public ProductResponse create(ProductRequest req) {
        if (productRepository.existsBySku(req.sku())) {
            throw new BusinessException(HttpStatus.CONFLICT, "SKU already exists");
        }
        Product saved = productRepository.save(Product.builder()
                .sku(req.sku())
                .name(req.name())
                .description(req.description())
                .price(req.price())
                .stock(req.stock())
                .build());
        return ProductResponse.from(saved);
    }

    @Override
    @Transactional
    public ProductResponse update(Long id, ProductRequest req) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "Product not found"));
        if (!product.getSku().equals(req.sku()) && productRepository.existsBySku(req.sku())) {
            throw new BusinessException(HttpStatus.CONFLICT, "SKU already exists");
        }
        product.setSku(req.sku());
        product.setName(req.name());
        product.setDescription(req.description());
        product.setPrice(req.price());
        product.setStock(req.stock());
        return ProductResponse.from(productRepository.save(product));
    }

    @Override
    @Transactional
    public void delete(Long id) {
        if (!productRepository.existsById(id)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "Product not found");
        }
        productRepository.deleteById(id);
    }
}
