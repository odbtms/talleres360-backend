# Talleres360 — Contexto del proyecto

Evaluación Final Transversal DSY1107 (Desarrollo Cloud Native I). Caso "Talleres360": plataforma de órdenes de trabajo para una red de 20 talleres mecánicos.

- Enunciado: `Evaluación Final Transversal — Instrucciones del Caso — Docente.pdf` (en esta carpeta).
- Tutorial del profe (guía a replicar): `Tutorial_Entra_ID_React_SpringBoot_Parte1.pdf` (en esta carpeta). La Parte 2 (EC2, HTTPS, API Gateway) todavía no la tenemos.
- Los PDF **no se publican**: `*.pdf` está en `.gitignore`.

**Preferencias del usuario:** responder en español, conciso, sin explayarse; ir paso a paso y confirmar el flujo antes de aplicar cambios grandes.

## Repositorios y carpetas

| Carpeta local | Repo | Contenido |
|---|---|---|
| `taller-360/taller-360/` (esta) | `talleres360-backend` (público) | Microservicios + `infra/` |
| `taller-360/talleres360-frontend/` | `talleres360-frontend` (nombre propuesto, falta confirmar) | SPA React |

Ambos tienen `git init` y un commit inicial en `main`. **Aún no se hizo push**: `gh` no tiene sesión (el usuario debe ejecutar `! gh auth login`, después crear el repo con `gh repo create talleres360-backend --public --source . --push`).

Backend en monorepo a propósito: la EC2 hace `git clone` + `docker compose up` y el compose construye cada micro desde su carpeta.

## Lo que está hecho

### ms-talleres360-orders (`ms-talleres360-orders/`)
- Spring Boot 3.5.6, **Java 17** (único JDK instalado; el caso pide 21 y el tutorial 25 → pendiente subir).
- Paquete `com.talleres360.orders`: `controller`, `service`, `repository`, `model`, `dto`, `exception`, `config`.
- Entidades `WorkOrder` + `OrderItem` (items con `productId` → futuro ms-catalog). Guarda `acceptedAt` y `deliveredAt` (para lead time).
- Estados (`OrderStatus`): `RECIBIDA → ACEPTADA → EN_REPARACION → LISTA_PARA_ENTREGA → ENTREGADA`, `CANCELADA` desde cualquiera antes de entregar. Transición inválida → **409** (regla: no entregar sin aceptar).
- Endpoints:
  - `POST /api/orders` (201)
  - `GET /api/orders/{id}` (404 si no existe)
  - `GET /api/orders?status=&from=&to=` (fechas ISO `2026-09-10T00:00:00`)
  - `PUT /api/orders/{id}` (solo en `RECIBIDA`, si no 409)
  - `PUT /api/orders/{id}/status` body `{ "status": "ACEPTADA" }`
  - `DELETE /api/orders/{id}` (204)
- Errores con `ProblemDetail` (`GlobalExceptionHandler`): 400 validación, 404, 409.
- Filtros con **JPA Specifications** (`WorkOrderRepository.filter`). No usar `:param IS NULL` en JPQL: PostgreSQL falla con "could not determine data type of parameter".
- Perfiles: `local` (H2 en memoria, por defecto y en tests) y `postgres` (variables `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`).
- CORS (`CorsConfig`) para `CORS_ALLOWED_ORIGINS` (default `http://localhost:5173`). Es temporal: cuando existan BFF y API Gateway, CORS va en API Gateway.
- Swagger: `/swagger-ui/index.html`. Colección Postman: `ms-talleres360-orders/postman/`.
- Test de integración `WorkOrderControllerTest` (flujo completo + regla 409) pasando.
- Dockerfile multi-stage (cache de dependencias, JRE 17, usuario no root).
- TODOs en `WorkOrderService`: descontar stock al aceptar (ms-catalog), publicar eventos Kafka (`orders.events`), notificación RabbitMQ (`email.send`).

### Infra (`infra/apps/compose.yml`)
- Servicios `postgres` (16-alpine, volumen `pgdata`, healthcheck) y `orders` (puerto 8081, `TZ=America/Santiago`, espera a Postgres healthy). Red `talleres360`.
- Credenciales de prueba `talleres360/talleres360`; las reales van en `infra/apps/.env` (ignorado por git).

### Frontend (`../talleres360-frontend/`)
- React 19 + Vite 8, **JavaScript** (el tutorial usa TypeScript → pendiente migrar).
- Puerto fijo 5173 (`strictPort`).
- Pantalla única: tabla con filtros (estado, desde/hasta), formulario crear/editar con ítems, panel de detalle con botones solo para transiciones válidas, eliminar, alerta con errores del backend.
- `src/api/http.js`: URL base desde `VITE_API_URL` + `setTokenProvider()` como gancho para que MSAL agregue `Authorization: Bearer`.
- `src/constants/orderStatus.js` replica las transiciones de `OrderStatus.java`.
- Aún **sin login**: llama directo a `localhost:8081`.

## Arquitectura objetivo (acordada, aún no implementada)

Entra ID **no** está en medio de las llamadas: solo emite el token. "SPA" y "API" en Entra son **registros de aplicación** (identidades), no servidores. API Gateway **no** enruta directo a los micros: enruta al **BFF**, y el BFF reparte a los microservicios por la red interna de Docker.

```
                         ┌──────────────────────── Microsoft Entra ID (tenant) ───────────────────────────┐
                         │  spa-fullstack  = identidad del front (SPA_CLIENT_ID, redirect URIs)            │
                         │  api-fullstack  = identidad del backend (API_CLIENT_ID, scope access_as_user,   │
                         │                   app roles Admin / Operador / Cliente, token v2)               │
                         └───────────▲──────────────────────────────────────────────┬──────────────────────┘
                                     │ 1. login (popup MSAL)                        │ claves públicas (JWKS)
                                     │ 2. access token (JWT)                        │
                                     │                                              ▼
┌─────── PC local ────┐     ┌────────────── AWS ──────────────────────────────────────────────────────────┐
│  Front React (SPA)  │  3. │  API Gateway (HTTP API)          EC2 (Docker Compose)                        │
│  localhost:5173     │─────┼─▶ JWT Authorizer ──4. enruta──▶  ms-talleres360-bff ──5.──▶ ms-orders ─▶ Postgres
│  MSAL + Bearer      │     │  • firma, issuer, audience      • vuelve a validar JWT      ms-catalog / report
│                     │◀────┼─ • CORS localhost:5173          • rol vs endpoint           (red interna,
└─────────────────────┘     │  • inválido → 401               • 401 / 403                  no expuestos)
                            └──────────────────────────────────────────────────────────────────────────────┘
```

Secuencia (ej. Operador acepta una orden):
```
Front(MSAL) ── login ──▶ Entra ID ──▶ access token (aud=API_CLIENT_ID, roles=[Operador])
Front ── PUT /api/orders/7/status + Bearer ──▶ API Gateway (valida JWT) ──▶ BFF (valida JWT + rol) ──▶ ms-orders ──▶ 200
```

- Issuer esperado: `https://login.microsoftonline.com/<TENANT_ID>/v2.0`; audience: `API_CLIENT_ID` (requiere `requestedAccessTokenVersion: 2` en el manifest de api-fullstack; con v1 el issuer es `sts.windows.net` y todo falla).
- La rúbrica pide validar el JWT **en API Gateway y en Spring** (`issuer-uri`) y controlar el rol por endpoint.

## Tutorial del profe → esta app

| Tutorial | Talleres360 |
|---|---|
| Registros `api-fullstack` y `spa-fullstack` (single tenant) | Igual. El usuario ya creó tenant y registro de API; falta confirmar SPA |
| Manifest `requestedAccessTokenVersion: 2` | Igual (obligatorio) |
| Scope `api://<API_CLIENT_ID>/access_as_user` | Igual + **app roles** Admin/Operador/Cliente (no están en el tutorial) |
| SPA redirect URIs `http://localhost:5173/redirect.html` y `http://localhost:5173`; sin implicit grant, sin secreto | Igual |
| Permiso delegado access_as_user + consentimiento admin; "Se requiere asignación" = No | Igual |
| Front `entra-demo`: Vite + TS, `@azure/msal-browser@5` + `@azure/msal-react@5`, `loginPopup`, `redirect.html` con `broadcastResponseToMainFrame`, Vite multipágina, `cacheLocation: sessionStorage`, `acquireTokenSilent` → fallback `acquireTokenPopup` | Aplicar al front de Talleres360 |
| `.env.local`: `VITE_ENTRA_TENANT_ID`, `VITE_SPA_CLIENT_ID`, `VITE_API_CLIENT_ID`, `VITE_API_BASE_URL` | Usar estos nombres (reemplaza `VITE_API_URL`) |
| Backend `api`: Spring Boot 3.5.x, Web + OAuth2 Resource Server, `issuer-uri` + `audiences`, `STATELESS`, csrf off, `anyRequest().denyAll()` | Es el **BFF** |
| `hasAuthority("SCOPE_access_as_user")` | + mapear claim `roles` → `ROLE_Admin`, etc. (`JwtGrantedAuthoritiesConverter`) |
| H5: 401 sin token / 200 con token (curl desde PowerShell, token con `Read-Host`) | Igual + 403 con rol incorrecto |
| JDK 25 | Instalar JDK 25 (hoy solo hay 17) |

## Pendientes (en orden)

1. [ ] `! gh auth login` → crear y pushear `talleres360-backend` (público). Confirmar nombre/visibilidad del repo del front y pushearlo.
2. [ ] **Entra ID** (portal):
   - api-fullstack: manifest v2, exponer API `api://<API_CLIENT_ID>`, scope `access_as_user`, **app roles** Admin/Operador/Cliente.
   - spa-fullstack: plataforma SPA con las dos redirect URIs, permiso delegado + consentimiento admin.
   - Usuarios de prueba (ej. alumno01…) con roles asignados en Aplicaciones empresariales > api-fullstack > Usuarios y grupos.
   - Anotar `TENANT_ID`, `API_CLIENT_ID`, `SPA_CLIENT_ID` (no son secretos; nunca pegar client secrets ni tokens en el chat).
3. [ ] **Front**: migrar a TypeScript, renombrar variables a las del tutorial, agregar MSAL 5 (authConfig, token, redirect.html, Vite multipágina), login/logout, conectar `setTokenProvider`, mostrar/ocultar acciones según rol. Hito H4.
4. [ ] **JDK 25** instalado; subir `java.version` y la imagen del Dockerfile de orders.
5. [ ] **ms-talleres360-bff**: Resource Server (issuer-uri + audience), roles por endpoint, reenvío a ms-orders por la red interna (`http://orders:8081`). Hito H5 (401/200/403). Agregar al compose.
6. [ ] Ajustar compose: solo exponer el BFF; orders y Postgres sin puertos públicos. Mover CORS fuera del micro.
7. [ ] **Parte 2**: EC2 con Docker Compose, API Gateway HTTP API con JWT Authorizer (issuer/audience de Azure) + CORS + rutas `/api/orders/*` (luego catalog/report) → BFF, `VITE_API_BASE_URL` = URL del Gateway. Security Groups mínimos.
8. [ ] Resto del caso: ms-catalog (stock decrece al aceptar), ms-report (Kafka), ms-notify (RabbitMQ), ms-audit (Kafka); `infra/mq` (RabbitMQ 2 nodos, 3 colas + DLQ, exchanges direct/topic/dlx, micro administrador) e `infra/kafka` (3 ZK + 3 brokers, tópicos `orders.events` y `audit.timeline` con 3 particiones/3 réplicas, Kafka-UI, micro administrador).

## Comandos útiles

```bash
# Backend en Docker (desde esta carpeta)
docker compose -f infra/apps/compose.yml up -d --build
docker compose -f infra/apps/compose.yml logs -f orders
docker compose -f infra/apps/compose.yml down

# Tests del micro (H2)
cd ms-talleres360-orders && ./mvnw test

# Front
cd ../talleres360-frontend && npm run dev   # http://localhost:5173
```

## Notas / gotchas
- Docker Desktop a veces no está iniciado: `Start-Process "C:\Program Files\Docker\Docker\Docker Desktop.exe"`.
- En PowerShell 5.1, `@(Invoke-RestMethod ...).Count` cuenta 1 para un arreglo JSON vacío: revisar el JSON crudo con `Invoke-WebRequest`.
- `target/` y `dist/` son salidas de build (ignoradas); se regeneran solas.
