package com.talleres360.orders.service;

import com.talleres360.orders.dto.ProductResponse;
import com.talleres360.orders.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service @RequiredArgsConstructor
public class ProductService {
	private final ProductRepository repository;
	@Transactional(readOnly = true)
	public List<ProductResponse> findAll() {
		return repository.findAllByOrderByNameAsc().stream().map(ProductResponse::from).toList();
	}
}
