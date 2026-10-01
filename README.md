# Talleres360 — Backend

Para reconstruir en otra cuenta AWS las dos EC2, sus grupos de seguridad y la HTTP API, sigue [la guía completa de despliegue](DESPLIEGUE_EC2_API_GATEWAY.md). Esta página resume el proyecto y sus límites; la guía contiene la secuencia de instalación y las comprobaciones.

Tres microservicios Spring Boot/Java 17 independientes: órdenes/agendamientos, catálogo/stock y reportería/auditoría. El frontend obtiene el token de Microsoft Entra ID; API Gateway y el BFF lo validan antes de llamar a los servicios. El BFF aplica los permisos por rol; los micros no validan el JWT del usuario. Los puertos 8081–8083 **no** deben quedar abiertos a Internet.

## Ubicación de cada archivo

```text
talleres360-backend/
├─ ms-talleres360-orders/       API, reglas, persistencia y pruebas
├─ ms-talleres360-catalog/      Productos, precios y stock; base propia
├─ ms-talleres360-report/       Ventas de entregas y eventos auditados; base propia
├─ infra/ms/compose.yml        Tres micros + tres PostgreSQL en EC2 backend
├─ infra/ms/.env.example       Plantilla de infra/ms/.env (EC2 backend)
├─ infra/apps/compose.yml      BFF + tres micros + tres PostgreSQL en una PC
└─ infra/apps/.env.example     Plantilla de infra/apps/.env (una PC)
```

El BFF está en el repositorio hermano `talleres360-bff` y el cliente en `talleres360-frontend`. Para `infra/apps/compose.yml`, deja las tres carpetas como hermanas: la ruta de build del BFF depende de ello. En AWS se usan dos instancias, no ese Compose local.

## Reproducir todo en otra PC

### Dónde se configuran los identificadores

| Entorno | Archivo que debes crear desde `.env.example` | Valores que debes poner |
| --- | --- | --- |
| Frontend (en tu PC) | `talleres360-frontend/.env.local` | `VITE_ENTRA_TENANT_ID` (ID del tenant), `VITE_SPA_CLIENT_ID` (ID de la aplicación SPA), `VITE_API_CLIENT_ID` (ID de la aplicación API), `VITE_API_BASE_URL` (BFF local o URL de API Gateway). |
| BFF en EC2 | `talleres360-bff/.env` | `ENTRA_TENANT_ID` y `API_CLIENT_ID` del **mismo tenant y registro API** que el frontend; `ORDERS_URL`, `CATALOG_URL`, `REPORT_URL` hacia la EC2 backend; `INTERNAL_API_KEY`. |
| Backend en EC2 | `talleres360-backend/infra/ms/.env` | `DB_USERNAME`, `DB_PASSWORD`, `INTERNAL_API_KEY` igual a la del BFF. **No** necesita IDs de Entra: el BFF valida el token. |
| Stack completo local | `talleres360-backend/infra/apps/.env` | Credenciales de DB, `INTERNAL_API_KEY`, `ENTRA_TENANT_ID` y `API_CLIENT_ID`, porque este Compose también levanta el BFF. |

No edites las plantillas `.env.example` para guardar secretos; copia cada una a su archivo `.env` real, ignorado por Git. Si usas otro tenant de Entra, tendrás que configurar sus **dos registros** (SPA y API), scope `access_as_user`, roles `Cliente`/`Operador`/`Admin`, usuarios asignados y URI de redirección de la SPA. El frontend y BFF deben apuntar a ese mismo tenant/API. La cuenta AWS puede ser distinta de la cuenta o tenant de Microsoft; son configuraciones independientes.

1. Instala Git, Docker con Compose (Docker Desktop en Windows) y Node.js para el frontend. Inicia Docker.
2. Clona las ramas que vas a usar, en un mismo directorio. Sustituye los remotos si usas tus propios forks:

   ```bash
   git clone -b backend-emmanuel https://github.com/odbtms/talleres360-backend.git
   git clone -b bff-emmanuel https://github.com/EmmanuelhxGG/talleres360-bff.git
   git clone -b fronted https://github.com/odbtms/talleres360-frontend.git
   ```

3. En `talleres360-backend/infra/apps/`, copia `.env.example` a `.env` y completa:

   ```dotenv
   DB_USERNAME=talleres360
   DB_PASSWORD=<contraseña-fuerte-propia>
   INTERNAL_API_KEY=<clave-aleatoria-larga-compartida-con-BFF-y-los-tres-micros>
   ENTRA_TENANT_ID=<Id-de-directorio-tenant-de-Entra>
   API_CLIENT_ID=<Id-de-aplicación-cliente-del-registro-API>
   CORS_ALLOWED_ORIGINS=http://localhost:5173
   SECURITY_LOG_LEVEL=INFO
   ```

   Los IDs se obtienen en Entra ID; el README del frontend explica los dos registros. `API_CLIENT_ID` es el ID de la **API**, nunca el de la SPA. No copies la contraseña de ejemplo a un entorno real.
4. Desde `talleres360-backend/`:

   ```bash
   docker compose -f infra/apps/compose.yml config
   docker compose -f infra/apps/compose.yml up -d --build
   docker compose -f infra/apps/compose.yml ps
   ```

   BFF: `http://localhost:8080`. Los tres microservicios y sus bases quedan internos en Docker. En `talleres360-frontend/.env.local` configura `VITE_API_BASE_URL=http://localhost:8080`, **no** 8081. Arranca el cliente según su README.
5. Diagnóstico:

   ```bash
   docker compose -f infra/apps/compose.yml logs --tail=100 orders catalog report bff
   ```

Los volúmenes PostgreSQL conservan órdenes, catálogo y auditoría entre recreaciones. `up -d --build` no los borra; **no uses `down -v`** si deseas conservar datos.

Para trabajar solo en orders, entra en `ms-talleres360-orders/` y ejecuta `./mvnw spring-boot:run` (Windows: `mvnw.cmd spring-boot:run`). Usa H2 en memoria por defecto; los datos se pierden al detenerlo. Swagger: `http://localhost:8081/swagger-ui.html`. Para probar operaciones que consultan productos o publican eventos necesitas levantar también catálogo y reportería, además de configurar `CATALOG_URL`, `REPORT_URL` e `INTERNAL_API_KEY`; la aplicación completa sigue necesitando BFF y Entra.

## Desplegar en tu EC2 de backend

La EC2 backend necesita Git y Docker con Compose; debe comunicarse por red privada con la EC2 BFF, idealmente en la misma VPC. Conéctate con **tu propia llave**: `ssh -i <ruta-a-tu-llave.pem> ec2-user@<DNS-o-IP-de-tu-EC2-backend>`. No subas el PEM al repositorio.

1. Clona este repositorio en la EC2 y selecciona `backend-emmanuel` (o tu rama de despliegue). Si ya está clonado, comprueba `git branch --show-current` y `git status` antes de `git pull --ff-only`; no actualices `main` por accidente.
2. En la EC2, copia `infra/ms/.env.example` a `infra/ms/.env` y sustituye `DB_PASSWORD` por una contraseña fuerte. Define `INTERNAL_API_KEY` con una clave aleatoria larga y coloca **la misma** en `talleres360-bff/.env` de la otra instancia. Ajusta `DB_USERNAME` si corresponde. `CORS_ALLOWED_ORIGINS` puede quedar en localhost: el navegador no debe entrar directo a los micros.
3. Desde `talleres360-backend/`:

   ```bash
   docker compose -f infra/ms/compose.yml config
   docker compose -f infra/ms/compose.yml up -d --build
   docker compose -f infra/ms/compose.yml ps
   docker compose -f infra/ms/compose.yml logs --tail=100 orders catalog report
   ```

4. Anota la **IP privada** de esta EC2. En `talleres360-bff/.env` de la otra EC2 configura `ORDERS_URL=http://<IP-privada-backend>:8081`, `CATALOG_URL=http://<IP-privada-backend>:8082` y `REPORT_URL=http://<IP-privada-backend>:8083`. El Security Group de backend debe permitir TCP 8081–8083 **solo desde el Security Group de BFF**. PostgreSQL no publica puertos; limita SSH 22 a tu IP.
5. Para publicar nuevos commits en esta misma rama: `git pull --ff-only` y luego `docker compose -f infra/ms/compose.yml up -d --build`. `restart: unless-stopped` reinicia los contenedores tras un reboot si Docker está habilitado; no descarga nuevas versiones por sí solo.

Si cambia la IP privada del backend, actualiza las tres URL en el BFF y recrea ese contenedor. La IP pública de backend no se utiliza en esas conexiones.

### Si las EC2 pertenecen a otra cuenta AWS

Si **ambas** EC2 se crean de nuevo en una misma VPC de la nueva cuenta, sigue los pasos anteriores: sustituye en `talleres360-bff/.env` la IP privada del backend, configura Security Groups, crea el API Gateway de **esa cuenta** y cambia `VITE_API_BASE_URL` del frontend a su nueva URL. No necesitas cambiar los IDs de Entra si conservas el mismo tenant y los registros de aplicación; sí debes registrar cualquier dominio nuevo del frontend como URI `/redirect.html` de la SPA y origen CORS.

Si BFF y backend quedan en **cuentas/VPC diferentes**, **sus IP privadas no se alcanzan automáticamente**. Antes de usar `ORDERS_URL=http://<IP-privada-backend>:8081` (y 8082/8083), establece conectividad entre VPC, por ejemplo VPC peering aceptado por ambas cuentas, CIDR no solapados, rutas hacia la VPC par en ambas tablas de enrutamiento y reglas de Security Group/NACL para 8081–8083. En peering de la misma región se puede referenciar el Security Group de la otra cuenta usando ID de cuenta/SG; entre regiones usa CIDR, no referencia de SG. Prueba desde la EC2 BFF la conectividad a los tres puertos antes de arrancar el BFF. Alternativa: exponer una API privada mediante un balanceador/VPC Link u otra conexión administrada; implica más infraestructura y costo. **No abras 8081–8083 a `0.0.0.0/0` para evitar configurar la red privada.**

## Endpoints y estado real

| Ruta | Función |
| --- | --- |
| `POST /api/appointments` | Solicitud de cliente; el BFF deriva su correo del JWT y envía `X-Customer-Email`. |
| `GET /api/appointments` | Historial del cliente autenticado. |
| `GET /api/appointments/availability?workshopId=&from=&to=` | Fechas por taller (`AAAA-MM-DD`). |
| `GET /api/orders`, `GET /api/orders/{id}` | Listar y consultar órdenes para operador/admin. |
| `POST /api/orders`, `PUT /api/orders/{id}` | Crear orden y editarla en estado recibido. |
| `PUT /api/orders/{id}/status` | Cambiar estado. |
| `PUT /api/orders/{id}/technical` | Diagnóstico, trabajo, mano de obra, entrega estimada y repuestos. |
| `DELETE /api/orders/{id}` | Eliminar orden (solo Admin a través del BFF). |
| `GET/POST /api/products`, `PUT /api/products/{id}` | Catálogo: consulta para Operador/Admin; escritura solo Admin a través del BFF. |
| `GET /api/reports/sales?from=&to=` | Ventas de órdenes entregadas; fechas ISO 8601 con zona horaria. Solo Admin. |
| `GET /api/reports/audit?orderId=` | Quién creó, cambió, trabajó y entregó órdenes. Solo Admin. |

Estados: `RECIBIDA → ACEPTADA → EN_REPARACION → LISTA_PARA_ENTREGA → ENTREGADA`. Se puede pasar a `CANCELADA` desde cualquiera de los cuatro estados previos a la entrega; `ENTREGADA` y `CANCELADA` son finales. Para marcar `LISTA_PARA_ENTREGA` deben existir diagnóstico y trabajo realizado. El backend vuelve a validar RUT, patente, teléfono, fechas, cantidades y otros datos; no depende únicamente de la UI. En `POST /api/orders` y `PUT /api/orders/{id}`, cada ítem contiene solo `productId` y `quantity`: el precio se consulta en Catálogo. La edición general de la orden solo se permite mientras esté `RECIBIDA`.

El Operador ejecuta ese flujo sin esperar aprobación; Admin supervisa y puede intervenir. El BFF deriva `X-Actor-Email` y `X-Actor-Role` del JWT. Si Admin cambia un estado, el cuerpo `PUT /api/orders/{id}/status` debe incluir `{"status":"ACEPTADA","reason":"Motivo de al menos 10 caracteres"}`; el motivo queda en auditoría. Estos encabezados no deben aceptarse desde un cliente sin pasar por BFF.

El catálogo es dueño del precio y stock; orders consulta el producto y **no acepta el precio enviado por el navegador**. Al entregar, orders escribe un evento persistente (outbox); un publicador intenta descontar stock en Catálogo y, después, registra la venta/auditoría en Reportería. Cada consumidor reconoce el ID de evento para evitar duplicados en reintentos. La venta se contabiliza **solo en `ENTREGADA`**, nunca al aceptar.

**Limitaciones importantes:** la sincronización es eventual (sondeo de 1 segundo más latencia de red), no una transacción distribuida ni tiempo real estricto. Si Catálogo cae o el stock cambia entre comprobación y entrega, la orden puede quedar `ENTREGADA` con movimiento/venta pendientes; revisa logs y la tabla `order_outbox` antes de cerrar caja. Para producción harían falta reservas de stock, una saga/compensación o confirmación de entrega posterior al descuento, además de alertas y conciliación. `availability` sigue devolviendo fechas ocupadas vacías. Si existían filas en la antigua tabla `PRODUCTS` de orders, **no se migran automáticamente** al nuevo catálogo. `ddl-auto=update` sigue siendo de prototipo: se requieren migraciones y backups.

La entrega estimada es un dato registrado por el operador o administrador; no genera notificaciones ni asigna cupos automáticamente. El catálogo registra consumos de entrega de forma idempotente, pero los ajustes manuales de stock todavía no tienen un historial de movimientos. La auditoría conserva los eventos enviados desde orders; no es un registro completo de cambios de catálogo ni un reemplazo de logs de seguridad.

## Verificación realizada y despliegue pendiente

Se ejecutaron pruebas automatizadas de los tres micros y del BFF, `npm run build` del frontend y validación de la configuración Compose. **No se hizo una prueba integral con los seis contenedores ni se desplegó esta versión en EC2**: Docker no estaba disponible durante esa verificación. Por tanto, la existencia de código y pruebas unitarias no demuestra que la integración AWS, las bases persistentes o los Security Groups ya estén funcionando. Antes de darla por operativa, levanta el stack, crea un producto, crea/atiende/entrega una orden y comprueba stock, ventas y auditoría en los tres servicios.

Los `.env`, la clave interna y llaves privadas son propios de cada instalación y no se suben a Git. Los micros no autentican usuarios finales por sí mismos: dependen del BFF y de mantener 8081–8083 privados. La clave interna añade una barrera, pero no sustituye aislamiento de red/TLS.
