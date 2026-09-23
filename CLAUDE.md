# Talleres360 — Contexto del proyecto

Evaluación Final Transversal DSY1107 (Desarrollo Cloud Native I). Caso "Talleres360": plataforma de órdenes de trabajo para una red de 20 talleres mecánicos.

- Enunciado: `Evaluación Final Transversal — Instrucciones del Caso — Docente.pdf` (en esta carpeta).
- Tutorial del profe (guía a replicar): `Tutorial_Entra_ID_React_SpringBoot_Parte1.pdf` (en esta carpeta). La Parte 2 (EC2, HTTPS, API Gateway) todavía no la tenemos.
- Los PDF **no se publican**: `*.pdf` está en `.gitignore`.

**Alcance real de esta evaluación (dicho por el profe):** basta con que funcione el **viaje del token** (Front MSAL → Entra ID → API Gateway → BFF → un microservicio con GET/POST/PUT/DELETE). El micro elegido es **ms-orders**. Catalog, report, notify, audit, RabbitMQ y Kafka del enunciado **no se piden** para esta entrega.

**Preferencias del usuario:** responder en español, conciso, sin explayarse; ir paso a paso y confirmar el flujo antes de aplicar cambios grandes.

## Repositorios y carpetas

| Carpeta local | Repo | Contenido |
|---|---|---|
| `taller-360/taller-360/` (esta) | https://github.com/odbtms/talleres360-backend (público) | Microservicios + `infra/` |
| `taller-360/talleres360-frontend/` | https://github.com/odbtms/talleres360-frontend (público) | SPA React |

Rama `main` en ambos, remoto `origin`. `gh` autenticado como `odbtms` (HTTPS).

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

### ms-talleres360-bff (`ms-talleres360-bff/`)
- Spring Boot 3.5.6, Java 17. Puerto **8080**. Es el único servicio expuesto hacia afuera.
- Solo hace lo del mapa: valida el JWT, autoriza por rol y reenvía. **Sin base de datos ni reglas de negocio** (no duplica los estados de la orden).
- `SecurityConfig`: `STATELESS`, csrf off, `anyRequest().denyAll()`; cada ruta exige `SCOPE_access_as_user` **y** un app role:
  - `GET /api/orders`, `GET /api/orders/**` → Admin, Operador, Cliente
  - `POST /api/orders` y `PUT /api/orders/**` (incluye `/status`) → Admin, Operador
  - `DELETE /api/orders/**` → Admin
- `jwtAuthenticationConverter` mapea el claim `roles` de Entra ID a `ROLE_Admin` / `ROLE_Operador` / `ROLE_Cliente` (Spring por defecto solo mapea `scp` → `SCOPE_*`).
- Validación del token por configuración (como el tutorial): `issuer-uri: https://login.microsoftonline.com/${ENTRA_TENANT_ID}/v2.0` + `audiences: ${API_CLIENT_ID}`. El decoder es lazy: la app arranca sin conexión a Entra, pero **sin esas dos variables no arranca** (placeholder sin default, a propósito).
- `OrdersProxyController`: `@RequestMapping("/api/orders/**")` reenvía método, query y body a `ORDERS_URL` (`http://orders:8081`) con `RestClient` y devuelve estado y cuerpo tal cual (incluidos los `ProblemDetail` 400/404/409). No reenvía el `Authorization`: orders está en la red interna.
- CORS propio (`CorsConfig`, `CORS_ALLOWED_ORIGINS`) marcado como temporal: cuando entre API Gateway se quita.
- Tests: `SecurityRulesTest` (401 sin token; 403 por rol incorrecto, por falta de scope y por ruta no mapeada) y `RolesClaimConverterTest`. El **200 del hito H5** se prueba end-to-end con el compose (requiere token real de Entra).

### Infra (`infra/apps/compose.yml`)
- Servicios `postgres` (16-alpine, volumen `pgdata`, healthcheck), `orders` y `bff`. Red `talleres360`.
- **Solo `bff` publica puerto (8080)**. `orders` usa `expose: 8081` y Postgres no publica nada (su mapeo quedó comentado por si hace falta inspeccionar la base).
- Variables en `infra/apps/.env` (ignorado por git); plantilla en `infra/apps/.env.example`: `DB_USERNAME`, `DB_PASSWORD`, `ENTRA_TENANT_ID`, `API_CLIENT_ID`, `CORS_ALLOWED_ORIGINS`.

### Frontend (`../talleres360-frontend/`)
- React 19 + Vite 8, **JavaScript** (el tutorial usa TypeScript → pendiente migrar).
- Puerto fijo 5173 (`strictPort`).
- Pantalla única: tabla con filtros (estado, desde/hasta), formulario crear/editar con ítems, panel de detalle con botones solo para transiciones válidas, eliminar, alerta con errores del backend.
- `src/api/http.js`: URL base desde `VITE_API_BASE_URL` (default `http://localhost:8081`) + `setTokenProvider()` que agrega `Authorization: Bearer`.
- `src/constants/orderStatus.js` replica las transiciones de `OrderStatus.java`.
- **Login con Entra ID ya implementado** igual que el tutorial: `@azure/msal-browser@5.21` + `@azure/msal-react@5.7`, `loginPopup` con `prompt: select_account`, `redirect.html` (`broadcastResponseToMainFrame`), Vite multipágina, `sessionStorage`, `acquireTokenSilent` → `acquireTokenPopup`, `logoutPopup`. Archivos en `src/auth/` (`authConfig.js`, `token.js`, `AuthGate.jsx`, `LoginPage.jsx`).
- Variables en `.env.local` (nombres del tutorial): `VITE_ENTRA_TENANT_ID`, `VITE_SPA_CLIENT_ID`, `VITE_API_CLIENT_ID`, `VITE_API_BASE_URL`.
- **Modo local**: si faltan los 3 IDs de Entra, el login muestra aviso + botón "Entrar en modo local (solo desarrollo)". Con los IDs completos el login Microsoft es obligatorio. Header muestra usuario y "Cerrar sesión".
- `AuthGate` monta la app recién después de registrar el token provider (si no, la primera llamada saldría sin token).
- Login real **aún no probado**: faltan los IDs reales de Entra ID.

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

## Pendiente del usuario (fuera del código)

- [x] Usuarios `operador01@` y `cliente01@tocortess.onmicrosoft.com` creados y con rol (Operador / Cliente); `tomas@` es Admin.
- [x] El profe **acepta React** en vez de Angular (el enunciado decía Angular 18+).
- [ ] Conseguir la **Parte 2** del tutorial (EC2, HTTPS, API Gateway) antes de armar la infra de AWS.
- [ ] Instalar **JDK 25** (lo pide el tutorial; hoy solo hay 17).

Capturas del portal se pegan en la terminal con `Alt + V`. **Nunca** enviar client secrets ni access tokens (`eyJ...`); los IDs sí se pueden mostrar.

## Pendientes (en orden)

1. [x] Repos creados y subidos a GitHub (backend y frontend, públicos).
2. [x] **Entra ID** listo (tenant `tocortess.onmicrosoft.com`). Registros reales: **`api-cloud`** (API) y **`spa-cloud`** (SPA) — no se llaman como en el tutorial.
   - api-cloud: `requestedAccessTokenVersion: 2`, `api://<API_CLIENT_ID>` con scope `access_as_user`, app roles Admin/Operador/Cliente (valor exacto, así los mapea el BFF).
   - spa-cloud: plataforma SPA con `http://localhost:5173/redirect.html` y `http://localhost:5173`; `access_as_user` delegado con consentimiento de administrador.
   - Los IDs están en `talleres360-frontend/.env.local` y `infra/apps/.env` (ambos fuera de git; los repos son públicos).
   - Usuarios de prueba: `tomas@` (Admin), `operador01@` (Operador), `cliente01@` (Cliente), todos `@tocortess.onmicrosoft.com`. Plan gratuito: solo se asignan usuarios, no grupos; cada fila de "Usuarios y grupos" es usuario+rol (editarla reemplaza el rol, para otro rol se agrega fila).
3. [~] **Front**: ✔ MSAL 5, login/logout, `setTokenProvider`, `.env.local` con los IDs reales y `VITE_API_BASE_URL=http://localhost:8080` (BFF). **Hito H4 probado OK**. ✔ Acciones según rol (`src/auth/roles.js` lee `roles` del **access token**, no del id token, porque los roles están en api-cloud; Admin todo, Operador sin eliminar, Cliente solo lectura; modo local = Admin), probado con los 3 usuarios. Falta (opcional) migrar a TypeScript.
4. [ ] **JDK 25** instalado; subir `java.version` y la imagen del Dockerfile de orders.
5. [x] **ms-talleres360-bff**: Resource Server (issuer-uri + audience), roles por endpoint, reenvío a ms-orders por la red interna (`http://orders:8081`), en el compose. **Hito H5 probado OK**: 401 sin token y 200 con token real (`Granted Authorities=[SCOPE_access_as_user, ROLE_Admin]`). Falta el 403 en vivo (necesita un usuario con otro rol).
6. [x] Compose: solo el BFF expone puerto; orders y Postgres quedaron internos. (CORS sigue en los micros hasta que exista API Gateway.)
7. [~] **AWS desplegado (Learner Lab, us-east-1)**, creado con AWS CLI (credenciales del lab en `~/.aws/credentials [default]`, caducan al reiniciar el lab):
   - EC2 `ec2-apps` `i-052299a94765b3fa1` (AL2023, t3.medium, 20 GB, key `vockey`), **Elastic IP `98.89.96.254`**. SSH: `ssh -i ~/.ssh/labsuser.pem ec2-user@98.89.96.254`. Repo en `~/talleres360-backend`, `.env` copiado por scp. User-data instala docker, compose, buildx y git.
   - SG `talleres360-apps` `sg-02b2b5b02009eebf6`: 22 solo desde la IP de casa (si cambia la red, actualizar la regla), 8080 abierto.
   - API Gateway HTTP API `talleres360-api` (`sapz07gi18`): **`https://sapz07gi18.execute-api.us-east-1.amazonaws.com`**. Autorizador JWT `entra-jwt` (issuer del tenant v2.0, audiences `API_CLIENT_ID` y `api://API_CLIENT_ID`, scope `access_as_user`). Rutas: `ANY /api/{proxy+}` (JWT) y `OPTIONS /api/{proxy+}` (sin auth, ambas → `http://98.89.96.254:8080/api/{proxy}`). CORS `http://localhost:5173`. Stage `$default` auto-deploy.
   - Gotcha: sin la ruta OPTIONS sin auth, el preflight CORS da 401 (la ruta ANY con JWT atrapa OPTIONS). Una ruta OPTIONS **sin target** tampoco sirve; tiene que ir a la integración.
   - Gotcha Git Bash: usar `MSYS_NO_PATHCONV=1` con el CLI (convierte `/aws/...` y `$default`); los SG no pueden llamarse `sg-*`.
   - Front local: `VITE_API_BASE_URL` = URL del Gateway.
   - Actualizar la EC2: `ssh ... 'cd talleres360-backend && git pull && docker compose -f infra/apps/compose.yml up -d --build'`.

   Plan original de este paso:
   - **EC2**: `git clone` del repo backend, crear a mano `infra/apps/.env` (no viaja en git: lleva `ENTRA_TENANT_ID`, `API_CLIENT_ID`, credenciales de la base y `CORS_ALLOWED_ORIGINS`), luego `docker compose -f infra/apps/compose.yml up -d --build`. El compose ya deja solo el BFF expuesto (8080).
   - **API Gateway (HTTP API)**: autorizador **JWT** con `Issuer = https://login.microsoftonline.com/73b420e9-.../v2.0` (el tenant) y `Audience = API_CLIENT_ID`; ruta `ANY /api/orders/{proxy+}` con integración HTTP hacia `http://<IP-EC2>:8080/api/orders/{proxy+}`; CORS con el origen del front. Después se agregan catalog y report igual.
   - **Security Group**: entrada 8080 (la integración HTTP de API Gateway sale por IPs públicas de AWS, no hay rango fijo; si se quiere cerrar de verdad, la alternativa es VPC Link con un ALB interno) y 22 solo desde tu IP. Postgres y orders no necesitan ninguna regla: viven en la red de Docker.
   - **Front**: `VITE_API_BASE_URL` = URL del Gateway. Si el front deja de correr en `localhost:5173`, hay que agregar la nueva URL en spa-cloud > Autenticación (redirect URIs) y en `CORS_ALLOWED_ORIGINS`.
   - La validación queda doble, como pide la rúbrica: API Gateway rechaza el token inválido y el BFF lo vuelve a validar y revisa el rol.
8. [—] **Fuera del alcance de esta entrega** (ver "Alcance real"). Resto del caso: ms-catalog (stock decrece al aceptar), ms-report (Kafka), ms-notify (RabbitMQ), ms-audit (Kafka); `infra/mq` (RabbitMQ 2 nodos, 3 colas + DLQ, exchanges direct/topic/dlx, micro administrador) e `infra/kafka` (3 ZK + 3 brokers, tópicos `orders.events` y `audit.timeline` con 3 particiones/3 réplicas, Kafka-UI, micro administrador).

## Cómo retomar

**Estado al 2026-09-22:** AWS construido (EC2 + API Gateway), pruebas sin token OK (401 en Gateway y BFF, preflight 200). **Falta la prueba end-to-end con login real** vía Gateway (Admin crea/acepta orden; cliente01 sin botones; 403 con curl contra el Gateway). EC2 quedó **detenida** (`aws ec2 stop-instances`), compose local abajo, dev server detenido.

Para retomar AWS: Start Lab → pegar credenciales nuevas en `~/.aws/credentials [default]` → `aws ec2 start-instances --instance-ids i-052299a94765b3fa1` (la Elastic IP no cambia y los contenedores arrancan solos por `restart: unless-stopped`) → `npm run dev` en el front (ya apunta al Gateway). Si la IP de casa cambió, actualizar la regla 22 del SG para poder entrar por SSH.


Todo quedó funcionando y **apagado** (compose abajo, dev server detenido). Para levantar de nuevo: iniciar Docker Desktop, `docker compose -f infra/apps/compose.yml up -d --build` y `npm run dev` en el front. Los archivos de entorno (`infra/apps/.env` y `talleres360-frontend/.env.local`) ya están escritos en el disco local; **no están en git**, así que en otra máquina (o en la EC2) hay que recrearlos a partir de `infra/apps/.env.example`.

## Comandos útiles

```bash
# Backend en Docker (desde esta carpeta)
docker compose -f infra/apps/compose.yml up -d --build
docker compose -f infra/apps/compose.yml logs -f bff
docker compose -f infra/apps/compose.yml down

# Tests
cd ms-talleres360-orders && ./mvnw test
cd ms-talleres360-bff && ./mvnw test

# Hito H5 (con el compose arriba)
curl -i http://localhost:8080/api/orders                              # 401
curl -i -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/orders   # 200 / 403 segun el rol

# Front
cd ../talleres360-frontend && npm run dev   # http://localhost:5173
```

## Notas / gotchas
- Docker Desktop a veces no está iniciado: `Start-Process "C:\Program Files\Docker\Docker\Docker Desktop.exe"`.
- En PowerShell 5.1, `@(Invoke-RestMethod ...).Count` cuenta 1 para un arreglo JSON vacío: revisar el JSON crudo con `Invoke-WebRequest`.
- `target/` y `dist/` son salidas de build (ignoradas); se regeneran solas.
- **403 en el BFF con token válido** = el token no trae el claim `roles` (el 401 sería firma/issuer/audience). Para diagnosticar: `SECURITY_LOG_LEVEL=DEBUG` en `infra/apps/.env` + `docker compose ... up -d bff`, y buscar `Granted Authorities=[...]` en los logs.
- Los app roles se asignan en **Aplicaciones empresariales > api-cloud > Usuarios y grupos**, no en el registro de aplicación.
- El rol se asigna a una identidad concreta: `tomas@tocortess.onmicrosoft.com` (nativo) y `to.cortess@duocuc.cl` (invitado `#EXT#`) son usuarios distintos. La contraseña de un invitado no se puede resetear desde el tenant; la de un usuario nativo sí (Entra ID > Usuarios > usuario > Restablecer contraseña).
- Tras asignar un rol hay que **cerrar sesión y volver a entrar**: MSAL cachea el token en `sessionStorage`.
