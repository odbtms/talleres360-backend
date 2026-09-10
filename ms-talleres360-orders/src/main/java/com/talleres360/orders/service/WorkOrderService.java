package com.talleres360.orders.service;

import com.talleres360.orders.dto.OrderRequest;
import com.talleres360.orders.dto.OrderResponse;
import com.talleres360.orders.exception.InvalidStatusTransitionException;
import com.talleres360.orders.exception.OrderNotFoundException;
import com.talleres360.orders.model.OrderItem;
import com.talleres360.orders.model.OrderStatus;
import com.talleres360.orders.model.WorkOrder;
import com.talleres360.orders.repository.WorkOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class WorkOrderService {

	private final WorkOrderRepository repository;

	@Transactional
	public OrderResponse create(OrderRequest request) {
		WorkOrder order = new WorkOrder();
		order.setStatus(OrderStatus.RECIBIDA);
		apply(order, request);
		// TODO: publicar OrderCreated en Kafka (orders.events) y comando email.send en RabbitMQ
		return OrderResponse.from(repository.save(order));
	}

	@Transactional(readOnly = true)
	public OrderResponse findById(Long id) {
		return OrderResponse.from(get(id));
	}

	@Transactional(readOnly = true)
	public List<OrderResponse> search(OrderStatus status, LocalDateTime from, LocalDateTime to) {
		return repository.findAll(WorkOrderRepository.filter(status, from, to), Sort.by(Sort.Direction.DESC, "createdAt"))
				.stream().map(OrderResponse::from).toList();
	}

	@Transactional
	public OrderResponse update(Long id, OrderRequest request) {
		WorkOrder order = get(id);
		if (order.getStatus() != OrderStatus.RECIBIDA) {
			throw new InvalidStatusTransitionException(
					"Solo se puede editar una orden en estado RECIBIDA (estado actual: " + order.getStatus() + ")");
		}
		apply(order, request);
		return OrderResponse.from(repository.save(order));
	}

	@Transactional
	public OrderResponse changeStatus(Long id, OrderStatus newStatus) {
		WorkOrder order = get(id);
		OrderStatus current = order.getStatus();

		if (!current.canTransitionTo(newStatus)) {
			throw new InvalidStatusTransitionException(
					"No se puede pasar de " + current + " a " + newStatus
							+ ". Permitidos: " + current.allowedTransitions());
		}

		order.setStatus(newStatus);
		LocalDateTime now = LocalDateTime.now();
		if (newStatus == OrderStatus.ACEPTADA) {
			order.setAcceptedAt(now);
			// TODO: descontar stock en ms-talleres360-catalog (regla: stock decrece al aceptar)
		}
		if (newStatus == OrderStatus.ENTREGADA) {
			order.setDeliveredAt(now);
		}
		// TODO: publicar evento de cambio de estado en Kafka y notificacion al cliente en RabbitMQ
		return OrderResponse.from(repository.save(order));
	}

	@Transactional
	public void delete(Long id) {
		repository.delete(get(id));
	}

	private WorkOrder get(Long id) {
		return repository.findById(id).orElseThrow(() -> new OrderNotFoundException(id));
	}

	private void apply(WorkOrder order, OrderRequest request) {
		order.setWorkshopId(request.workshopId());
		order.setCustomerName(request.customerName());
		order.setCustomerEmail(request.customerEmail());
		order.setVehiclePlate(request.vehiclePlate().toUpperCase());
		order.setVehicleModel(request.vehicleModel());
		order.setDescription(request.description());

		List<OrderRequest.Item> items = request.items() == null ? List.of() : request.items();
		order.replaceItems(items.stream().map(i -> {
			OrderItem item = new OrderItem();
			item.setProductId(i.productId());
			item.setQuantity(i.quantity());
			item.setUnitPrice(i.unitPrice());
			return item;
		}).toList());
	}
}
