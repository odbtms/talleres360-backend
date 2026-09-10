package com.talleres360.orders.dto;

import com.talleres360.orders.model.OrderItem;
import com.talleres360.orders.model.OrderStatus;
import com.talleres360.orders.model.WorkOrder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record OrderResponse(
		Long id,
		Long workshopId,
		String customerName,
		String customerEmail,
		String vehiclePlate,
		String vehicleModel,
		String description,
		OrderStatus status,
		BigDecimal total,
		List<Item> items,
		LocalDateTime createdAt,
		LocalDateTime updatedAt,
		LocalDateTime acceptedAt,
		LocalDateTime deliveredAt
) {
	public record Item(Long id, Long productId, Integer quantity, BigDecimal unitPrice, BigDecimal subtotal) {
		static Item from(OrderItem i) {
			return new Item(i.getId(), i.getProductId(), i.getQuantity(), i.getUnitPrice(), i.subtotal());
		}
	}

	public static OrderResponse from(WorkOrder o) {
		return new OrderResponse(
				o.getId(), o.getWorkshopId(), o.getCustomerName(), o.getCustomerEmail(),
				o.getVehiclePlate(), o.getVehicleModel(), o.getDescription(), o.getStatus(), o.getTotal(),
				o.getItems().stream().map(Item::from).toList(),
				o.getCreatedAt(), o.getUpdatedAt(), o.getAcceptedAt(), o.getDeliveredAt());
	}
}
