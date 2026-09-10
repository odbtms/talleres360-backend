package com.talleres360.orders.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.List;

public record OrderRequest(
		@NotNull Long workshopId,
		@NotBlank @Size(max = 120) String customerName,
		@NotBlank @Email @Size(max = 150) String customerEmail,
		@NotBlank @Size(max = 10) String vehiclePlate,
		@Size(max = 120) String vehicleModel,
		@Size(max = 1000) String description,
		@Valid List<Item> items
) {
	public record Item(
			@NotNull Long productId,
			@NotNull @Positive Integer quantity,
			@NotNull @PositiveOrZero BigDecimal unitPrice
	) {
	}
}
