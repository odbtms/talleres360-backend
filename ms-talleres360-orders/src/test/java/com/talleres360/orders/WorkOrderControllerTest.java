package com.talleres360.orders;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class WorkOrderControllerTest {

	@Autowired
	MockMvc mvc;

	private static final String ORDER = """
			{"workshopId":1,"customerName":"Juan Perez","customerEmail":"juan@mail.com",
			 "vehiclePlate":"abcd12","vehicleModel":"Toyota Yaris","description":"Cambio de aceite",
			 "items":[{"productId":10,"quantity":2,"unitPrice":15000}]}
			""";

	@Test
	void flujoCompletoYReglaNoEntregarSinAceptar() throws Exception {
		mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(ORDER))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").value(1))
				.andExpect(jsonPath("$.status").value("RECIBIDA"))
				.andExpect(jsonPath("$.total").value(30000));

		// No se puede entregar sin aceptar
		changeStatus(1, "ENTREGADA").andExpect(status().isConflict());

		changeStatus(1, "ACEPTADA").andExpect(status().isOk()).andExpect(jsonPath("$.acceptedAt").exists());
		changeStatus(1, "EN_REPARACION").andExpect(status().isOk());
		changeStatus(1, "LISTA_PARA_ENTREGA").andExpect(status().isOk());
		changeStatus(1, "ENTREGADA").andExpect(status().isOk()).andExpect(jsonPath("$.deliveredAt").exists());

		mvc.perform(get("/api/orders").param("status", "ENTREGADA"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1));

		mvc.perform(get("/api/orders/999")).andExpect(status().isNotFound());

		String appointmentDate = LocalDate.now().plusDays(10).toString();
		String appointment = """
				{"workshopId":5,"regionId":"biobio","firstName":"Cliente","lastName":"Prueba",
				 "rut":"12.345.678-5","phone":"12345678","vehiclePlate":"HD-JK-17",
				 "vehicleModel":"Toyota Corolla","vehicleYear":2022,"serviceType":"MAINTENANCE",
				 "reason":"Mantención preventiva completa","appointmentDate":"%s"}
				""".formatted(appointmentDate);

		mvc.perform(post("/api/appointments")
					.header("X-Customer-Email", "cliente@example.com")
					.contentType(MediaType.APPLICATION_JSON).content(appointment))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.customerEmail").value("cliente@example.com"))
				.andExpect(jsonPath("$.serviceType").value("MAINTENANCE"))
				.andExpect(jsonPath("$.appointmentDate").value(appointmentDate));

		mvc.perform(get("/api/appointments").header("X-Customer-Email", "cliente@example.com"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
		mvc.perform(get("/api/appointments").header("X-Customer-Email", "otro@example.com"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));

		mvc.perform(post("/api/appointments")
					.header("X-Customer-Email", "otro@example.com")
					.contentType(MediaType.APPLICATION_JSON).content(appointment))
				.andExpect(status().isConflict());

		mvc.perform(get("/api/appointments/availability")
					.param("workshopId", "5")
					.param("from", LocalDate.now().plusDays(1).toString())
					.param("to", LocalDate.now().plusDays(90).toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.occupiedDates[0]").value(appointmentDate));
	}

	private org.springframework.test.web.servlet.ResultActions changeStatus(long id, String status) throws Exception {
		return mvc.perform(put("/api/orders/" + id + "/status")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"" + status + "\"}"));
	}
}
