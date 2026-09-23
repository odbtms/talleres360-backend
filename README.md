# Talleres360 — Backend

Backend cloud-native (Spring Boot) para la gestión de órdenes de trabajo de una red de talleres mecánicos.

## Estructura

```
ms-talleres360-orders/   Microservicio de órdenes de trabajo (CRUD + estados)
infra/ms/compose.yml     Docker Compose de la VM de microservicios (ec2-apps)
infra/apps/compose.yml   Todo junto en local (construye el BFF desde ../talleres360-bff)
```

BFF (validación JWT de Entra ID + roles): repo aparte https://github.com/odbtms/talleres360-bff.

Próximos: `ms-talleres360-catalog`, `ms-talleres360-report`, `infra/mq`, `infra/kafka`.

## Levantar con Docker

```bash
docker compose -f infra/apps/compose.yml up -d --build
```

| Servicio | URL |
|---|---|
| ms-talleres360-orders | http://localhost:8081 |
| Swagger | http://localhost:8081/swagger-ui/index.html |
| PostgreSQL | localhost:5432 (`orders_db`) |

Credenciales de base de datos y orígenes CORS se sobreescriben con un archivo `infra/apps/.env`:

```
DB_USERNAME=...
DB_PASSWORD=...
CORS_ALLOWED_ORIGINS=http://localhost:5173
```

## Levantar sin Docker (H2 en memoria)

```bash
cd ms-talleres360-orders
./mvnw spring-boot:run
```

## Endpoints — ms-talleres360-orders

| Método | Ruta | Descripción |
|---|---|---|
| POST | `/api/orders` | Crear orden (estado `RECIBIDA`) |
| GET | `/api/orders/{id}` | Obtener orden |
| GET | `/api/orders?status=&from=&to=` | Listar con filtros (fechas ISO `2026-09-10T00:00:00`) |
| PUT | `/api/orders/{id}` | Editar (solo en `RECIBIDA`) |
| PUT | `/api/orders/{id}/status` | Cambiar estado `{ "status": "ACEPTADA" }` |
| DELETE | `/api/orders/{id}` | Eliminar |

Flujo de estados: `RECIBIDA → ACEPTADA → EN_REPARACION → LISTA_PARA_ENTREGA → ENTREGADA` (se puede `CANCELADA` antes de entregar). No se puede entregar sin aceptar (409).

Colección Postman: `ms-talleres360-orders/postman/`.
