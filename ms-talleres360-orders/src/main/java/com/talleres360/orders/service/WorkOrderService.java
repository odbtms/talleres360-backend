package com.talleres360.orders.service;

import com.talleres360.orders.dto.OrderRequest;
import com.talleres360.orders.dto.OrderResponse;
import com.talleres360.orders.dto.TechnicalUpdateRequest;
import com.talleres360.orders.exception.InvalidStatusTransitionException;
import com.talleres360.orders.exception.OrderNotFoundException;
import com.talleres360.orders.model.OrderItem;
import com.talleres360.orders.model.OrderStatus;
import com.talleres360.orders.model.WorkOrder;
import com.talleres360.orders.repository.WorkOrderRepository;
import com.talleres360.orders.repository.ProductRepository;
import com.talleres360.orders.validation.RutValidator;
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
	private final ProductRepository productRepository;

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
		if (newStatus == OrderStatus.LISTA_PARA_ENTREGA
				&& (order.getDiagnosis() == null || order.getDiagnosis().isBlank()
				|| order.getWorkPerformed() == null || order.getWorkPerformed().isBlank())) {
			throw new InvalidStatusTransitionException(
					"Debes registrar el diagnóstico y el trabajo realizado antes de marcar la orden como lista");
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
	public OrderResponse updateTechnicalDetails(Long id, TechnicalUpdateRequest request) {
		WorkOrder order = get(id);
		if (order.getStatus() != OrderStatus.ACEPTADA && order.getStatus() != OrderStatus.EN_REPARACION
				&& order.getStatus() != OrderStatus.LISTA_PARA_ENTREGA) {
			throw new InvalidStatusTransitionException(
					"Primero se debe aceptar la solicitud para registrar el diagnóstico y los costos");
		}

		order.setDiagnosis(request.diagnosis().trim());
		order.setWorkPerformed(request.workPerformed().trim());
		order.setLaborCost(request.laborCost());
		order.setEstimatedDeliveryDate(request.estimatedDeliveryDate());
		order.setTechnicalUpdatedAt(LocalDateTime.now());
		order.replaceItems(request.items().stream().map(i -> {
			var product = productRepository.findById(i.productId())
					.orElseThrow(() -> new IllegalArgumentException("El producto seleccionado no existe"));
			if (!product.isActive() || product.getStock() < i.quantity()) {
				throw new IllegalArgumentException("El producto " + product.getName() + " no tiene stock suficiente");
			}
			OrderItem item = new OrderItem();
			item.setProductId(product.getId());
			item.setDescription(product.getName());
			item.setQuantity(i.quantity());
			item.setUnitPrice(product.getPrice());
			return item;
		}).toList());
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
		if (!RutValidator.isValid(request.customerRut())) {
			throw new IllegalArgumentException("El RUT ingresado no es válido");
		}
		order.setWorkshopId(request.workshopId());
		order.setCustomerName(request.customerName());
		order.setCustomerEmail(request.customerEmail());
		order.setCustomerRut(request.customerRut());
		order.setCustomerPhone(request.customerPhone());
		order.setVehiclePlate(request.vehiclePlate().toUpperCase());
		order.setVehicleModel(request.vehicleModel());
		order.setDescription(request.description());

		List<OrderRequest.Item> items = request.items() == null ? List.of() : request.items();
		order.replaceItems(items.stream().map(i -> {
			OrderItem item = new OrderItem();
			item.setProductId(i.productId());
			item.setDescription("Producto #" + i.productId());
			item.setQuantity(i.quantity());
			item.setUnitPrice(i.unitPrice());
			return item;
		}).toList());
	}
}
