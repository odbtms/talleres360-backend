package com.talleres360.orders;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

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
	}

	private org.springframework.test.web.servlet.ResultActions changeStatus(long id, String status) throws Exception {
		return mvc.perform(put("/api/orders/" + id + "/status")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"" + status + "\"}"));
	}
}
