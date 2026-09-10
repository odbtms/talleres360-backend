package com.talleres360.orders.controller;

import com.talleres360.orders.dto.OrderRequest;
import com.talleres360.orders.dto.OrderResponse;
import com.talleres360.orders.dto.StatusUpdateRequest;
import com.talleres360.orders.model.OrderStatus;
import com.talleres360.orders.service.WorkOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
@Tag(name = "Ordenes de trabajo")
public class WorkOrderController {

	private final WorkOrderService service;

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Crear orden (queda en estado RECIBIDA)")
	public OrderResponse create(@Valid @RequestBody OrderRequest request) {
		return service.create(request);
	}

	@GetMapping("/{id}")
	@Operation(summary = "Obtener orden por id")
	public OrderResponse findById(@PathVariable Long id) {
		return service.findById(id);
	}

	@GetMapping
	@Operation(summary = "Listar ordenes filtrando por estado y rango de fechas de creacion (ISO: 2026-09-10T00:00:00)")
	public List<OrderResponse> search(
			@RequestParam(required = false) OrderStatus status,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
		return service.search(status, from, to);
	}

	@PutMapping("/{id}")
	@Operation(summary = "Editar datos de la orden (solo en estado RECIBIDA)")
	public OrderResponse update(@PathVariable Long id, @Valid @RequestBody OrderRequest request) {
		return service.update(id, request);
	}

	@PutMapping("/{id}/status")
	@Operation(summary = "Cambiar estado: RECIBIDA|ACEPTADA|EN_REPARACION|LISTA_PARA_ENTREGA|ENTREGADA|CANCELADA")
	public OrderResponse changeStatus(@PathVariable Long id, @Valid @RequestBody StatusUpdateRequest request) {
		return service.changeStatus(id, request.status());
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Eliminar orden")
	public void delete(@PathVariable Long id) {
		service.delete(id);
	}
}
