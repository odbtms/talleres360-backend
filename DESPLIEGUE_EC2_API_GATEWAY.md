# Talleres360: instalar en otra cuenta AWS

Sigue los pasos **en orden**. Resultado: una EC2 para `orders` + `catalog` + `report` y tres PostgreSQL; otra EC2 para BFF; API Gateway HTTP API delante del BFF. Ambas EC2 usan la **misma VPC existente**. Aquí no se modifica VPC, subnets ni rutas.

Los comandos son para **Amazon Linux 2023 x86_64** y se ejecutan como `ec2-user`. Reemplaza solo los textos entre `<...>`. No copies DNS, IP, tenant, contraseñas ni llaves de la cuenta anterior. Antes de desplegar, publica en Git los cambios locales de las ramas `backend-emmanuel` y `bff-emmanuel`; `git clone` no copia cambios sin commit/push.

> Lo verificable aquí es el código y los archivos Compose, **no** los ajustes exactos de tus recursos AWS anteriores: no inspeccioné su tipo de instancia, disco, API ID ni reglas actuales. Esta guía reproduce el flujo funcional, y el paso final exige probarlo. No afirmes que la nueva cuenta es idéntica hasta completar esas pruebas.

## 1. Crear los dos grupos de seguridad

En **EC2 → Grupos de seguridad → Crear grupo de seguridad**, crea ambos en la **misma VPC existente**:

| Nombre | Regla de entrada que debes agregar | Origen |
| --- | --- | --- |
| `sg-talleres360-backend` | SSH / TCP 22 | **Tu IP** (`x.x.x.x/32`) |
| `sg-talleres360-backend` | TCP 8081 | ID de `sg-talleres360-bff` |
| `sg-talleres360-backend` | TCP 8082 | ID de `sg-talleres360-bff` |
| `sg-talleres360-backend` | TCP 8083 | ID de `sg-talleres360-bff` |
| `sg-talleres360-bff` | SSH / TCP 22 | **Tu IP** (`x.x.x.x/32`) |
| `sg-talleres360-bff` | TCP 8080 | `0.0.0.0/0` para esta integración HTTP pública |

Si la consola no deja referenciar todavía `sg-talleres360-bff`, crea primero los dos grupos y luego edita las reglas entrantes del grupo backend. No abras 5432 ni 8081–8083 a `0.0.0.0/0`. No cambies las reglas de salida si la VPC existente ya permite descargar Git, imágenes y dependencias.

**Riesgo conocido:** el 8080 público también permite llegar directamente al BFF; su JWT protege las rutas, pero Gateway → BFF usa HTTP. Un Security Group no identifica exclusivamente a esta API Gateway. Esto reproduce la integración simple usada aquí, no una solución de producción con TLS privado. La alternativa es VPC Link + balanceador privado; sería otra arquitectura, **no** un paso de esta guía.

## 2. Crear las EC2

En **EC2 → Lanzar instancia**, crea:

| Instancia | AMI / arquitectura | VPC | Grupo |
| --- | --- | --- | --- |
| `ec2-talleres360-backend` | Amazon Linux 2023 / x86_64 | La VPC existente | `sg-talleres360-backend` |
| `ec2-talleres360-bff` | Amazon Linux 2023 / x86_64 | **La misma** VPC | `sg-talleres360-bff` |

Elige tu tipo de instancia, disco y una llave `.pem` de **la nueva cuenta**. No puedo indicar tamaños «exactos» de la cuenta original sin leerlos allí. Para seguir este método por SSH y para la integración HTTP, ambas instancias necesitan DNS/IP pública y salida a Internet con la configuración de red existente; si esa VPC no lo permite, detente y resuelve esa condición antes de continuar. No modifiques la VPC como parte de esta guía.

Anota: **IP privada del backend**, **DNS/IP pública del BFF** y **tu IP pública para SSH**. El DNS/IP público del backend se usa solo para administrarlo; **BFF llama a backend por su IP privada**.

## 3. Instalar Docker, Compose y Buildx en **cada** EC2

Desde PowerShell en tu PC, entra a **cada** instancia (cambia el DNS):

```powershell
ssh -i "C:\ruta\a\tu-llave.pem" ec2-user@<DNS-PUBLICO-EC2>
```

En la terminal Linux de la EC2, copia **este bloque**:

```bash
sudo dnf update -y
sudo dnf install -y git docker curl openssl
sudo systemctl enable --now docker
sudo usermod -aG docker ec2-user
```

**Cierra SSH y entra de nuevo** para aplicar el grupo `docker`. Luego instala los dos plugins CLI (estas URL son para x86_64):

```bash
sudo mkdir -p /usr/local/lib/docker/cli-plugins
sudo curl -fL --retry 3 https://github.com/docker/buildx/releases/download/v0.36.1/buildx-v0.36.1.linux-amd64 -o /usr/local/lib/docker/cli-plugins/docker-buildx
sudo curl -fL --retry 3 https://github.com/docker/compose/releases/download/v5.5.1/docker-compose-linux-x86_64 -o /usr/local/lib/docker/cli-plugins/docker-compose
sudo chmod +x /usr/local/lib/docker/cli-plugins/docker-buildx /usr/local/lib/docker/cli-plugins/docker-compose
```

**Comprueba los tres comandos antes de seguir:**

```bash
docker version
docker buildx version
docker compose version
```

Si falla cualquiera, **no ejecutes el build**. Buildx y Compose son plugins diferentes. La instalación manual queda fijada a esas versiones y **no recibe actualizaciones automáticas**; revisa periódicamente sus avisos de seguridad. [Docker documenta Buildx](https://github.com/docker/buildx#manual-download) y [Compose](https://docs.docker.com/compose/install/linux/) como plugins CLI.

## 4. Backend: clonar y configurar

Ahora trabaja **solo en la EC2 backend**:

```bash
git clone -b backend-emmanuel https://github.com/odbtms/talleres360-backend.git
cd talleres360-backend
git branch --show-current
cp infra/ms/.env.example infra/ms/.env
nano infra/ms/.env
```

En `infra/ms/.env` deja **estas claves** y reemplaza los valores `<...>`:

```dotenv
DB_USERNAME=talleres360
DB_PASSWORD=<CONTRASENA-FUERTE-NUEVA>
CORS_ALLOWED_ORIGINS=http://localhost:5173
INTERNAL_API_KEY=<CLAVE-ALEATORIA-LARGA>
```

Para generar la clave: `openssl rand -hex 32`. Copia **el mismo resultado** al `.env` del BFF por un medio seguro. Guarda en `nano` con `Ctrl+O`, `Enter`, `Ctrl+X`. No publiques estos valores ni los muestres en capturas.

```bash
chmod 600 infra/ms/.env
docker compose --env-file infra/ms/.env -f infra/ms/compose.yml config --quiet
docker compose --env-file infra/ms/.env -f infra/ms/compose.yml up -d --build
docker compose --env-file infra/ms/.env -f infra/ms/compose.yml ps
```

En `ps` deben aparecer `orders`, `catalog`, `report` y **tres** PostgreSQL. Si alguno falla:

```bash
docker compose --env-file infra/ms/.env -f infra/ms/compose.yml logs --tail=100 orders catalog report
```

No uses `docker compose down -v`: elimina datos persistidos. Los productos antiguos de orders **no** se importan automáticamente al catálogo nuevo.

## 5. BFF: clonar y configurar

Ahora trabaja **solo en la EC2 BFF**:

```bash
git clone -b bff-emmanuel https://github.com/EmmanuelhxGG/talleres360-bff.git
cd talleres360-bff
git branch --show-current
cp .env.example .env
nano .env
```

En `.env` deja **estas claves**. Escribe la **IP privada del backend**, sin `/dev` ni `/api`:

```dotenv
ENTRA_TENANT_ID=<ID-DEL-TENANT-DE-MICROSOFT-ENTRA>
API_CLIENT_ID=<ID-DEL-REGISTRO-API-NO-EL-DE-LA-SPA>
ORDERS_URL=http://<IP-PRIVADA-BACKEND>:8081
CATALOG_URL=http://<IP-PRIVADA-BACKEND>:8082
REPORT_URL=http://<IP-PRIVADA-BACKEND>:8083
INTERNAL_API_KEY=<MISMA-CLAVE-DEL-BACKEND>
CORS_ALLOWED_ORIGINS=http://localhost:5173
SECURITY_LOG_LEVEL=INFO
```

Guarda y prueba la red **antes de construir**:

```bash
chmod 600 .env
curl -i http://<IP-PRIVADA-BACKEND>:8081/api/orders
curl -i http://<IP-PRIVADA-BACKEND>:8082/api/products
```

La primera llamada debe obtener respuesta HTTP; la segunda, **401** por falta de `X-Internal-Key`. Un *timeout* significa problema de IP, Security Group o contenedor, no de Azure. Después:

```bash
docker compose config --quiet
docker compose up -d --build
docker compose ps
curl -i http://localhost:8080/api/orders
```

La última llamada sin token debe dar **401**. Si el BFF no inicia: `docker compose logs --tail=100 bff`.

## 6. API Gateway: escribir estos valores

En **API Gateway → Crear API → HTTP API** (no REST API):

1. **Nombre:** `talleres360-api`.
2. **Integración:** HTTP proxy / HTTP URL, método `ANY`. URI: `http://<DNS-O-IP-PUBLICA-BFF>:8080/{proxy}`.
3. **Ruta:** `ANY /{proxy+}` asociada a esa integración.
4. **Autorizador JWT:** origen `$request.header.Authorization`; *issuer* `https://login.microsoftonline.com/<ENTRA_TENANT_ID>/v2.0`; *audience* `<API_CLIENT_ID>` del registro **API**; scope de la ruta `access_as_user`. Asócialo a `ANY /{proxy+}`. Los roles los comprueba el BFF.
5. **CORS:** origen `http://localhost:5173`; métodos `GET, POST, PUT, DELETE, OPTIONS`; encabezados `authorization, content-type`. El preflight `OPTIONS` **no debe exigir JWT**. Si la ruta comodín lo intercepta con el autorizador, agrega `OPTIONS /{proxy+}` sin autorizador y vuelve a probar.
6. **Stage:** `dev`, con despliegue automático. Copia su URL: `https://<API-ID>.execute-api.<REGION>.amazonaws.com/dev`.

La ruta externa `/dev/api/orders` debe llegar al BFF como `/api/orders`. Si llega con `/dev` o sin `/api`, revisa la URI de integración antes de tocar Spring. [AWS documenta el proxy HTTP](https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-develop-integrations-http.html), el [JWT](https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-jwt-authorizer.html) y el [preflight CORS](https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-cors.html).

## 7. Frontend: cambiar la URL

En **tu PC**, edita `talleres360-frontend/.env.local`:

```dotenv
VITE_ENTRA_TENANT_ID=<MISMO-TENANT-DEL-BFF>
VITE_SPA_CLIENT_ID=<ID-DEL-REGISTRO-SPA>
VITE_API_CLIENT_ID=<MISMO-ID-API-DEL-BFF-Y-GATEWAY>
VITE_API_BASE_URL=https://<API-ID>.execute-api.<REGION>.amazonaws.com/dev
```

Reinicia `npm run dev`. No pongas `/api/orders` al final de la URL base. No pongas un secreto en ninguna variable `VITE_*`. Si cambia el dominio del frontend, registra también `<nuevo-origen>/redirect.html` en la SPA de Entra y el nuevo origen en CORS.

## 8. Probar antes de darlo por terminado

Desde **PowerShell en tu PC**, reemplaza la URL y ejecuta:

```powershell
curl.exe -i -X OPTIONS "https://<API-ID>.execute-api.<REGION>.amazonaws.com/dev/api/orders" -H "Origin: http://localhost:5173" -H "Access-Control-Request-Method: GET" -H "Access-Control-Request-Headers: authorization,content-type"
```

Esperado: **200/204** y `access-control-allow-origin: http://localhost:5173`. Repite con `/dev/api/products` y `Access-Control-Request-Method: POST`. Un 403 aquí suele ser ruta/stage/CORS/OPTIONS; todavía no es un fallo de los micros.

Luego entra al frontend con cuentas de cada rol y comprueba, en orden: **Cliente agenda → Operador acepta y registra trabajo → Operador entrega → baja el stock → Admin ve venta y auditoría**. El dashboard refresca aproximadamente cada 10 segundos. Si la orden figura `ENTREGADA` pero stock o venta no coinciden, revisa los logs y `order_outbox`; hay sincronización eventual, no transacción distribuida.

## 9. Actualizar después

En **cada EC2**, entra en su repositorio, confirma `git branch --show-current` y `git status`, luego usa `git pull --ff-only`. En backend repite `docker compose --env-file infra/ms/.env -f infra/ms/compose.yml up -d --build`; en BFF repite `docker compose up -d --build`. `restart: unless-stopped` enciende contenedores tras reboot, **no** descarga commits. No uses `down -v` si deseas conservar las bases.
