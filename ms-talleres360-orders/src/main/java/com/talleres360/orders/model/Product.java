package com.talleres360.orders.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "PRODUCTS")
@Getter @Setter @NoArgsConstructor
public class Product {
	@Id @GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Column(nullable = false, length = 160)
	private String name;
	@Column(nullable = false)
	private Integer stock;
	@Column(nullable = false, precision = 12, scale = 2)
	private BigDecimal price;
	@Column(nullable = false)
	private boolean active = true;
}
