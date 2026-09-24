package com.talleres360.orders.dto;

import com.talleres360.orders.model.Product;
import java.math.BigDecimal;

public record ProductResponse(Long id, String name, Integer stock, BigDecimal price, boolean available) {
	public static ProductResponse from(Product product) {
		return new ProductResponse(product.getId(), product.getName(), product.getStock(), product.getPrice(),
				product.isActive() && product.getStock() > 0);
	}
}
