package com.talleres360.orders.controller;

import com.talleres360.orders.dto.ProductResponse;
import com.talleres360.orders.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequestMapping("/api/products") @RequiredArgsConstructor
public class ProductController {
	private final ProductService service;
	@GetMapping public List<ProductResponse> findAll() { return service.findAll(); }
}
