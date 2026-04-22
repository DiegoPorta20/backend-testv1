# Arquitectura y conectividad entre servicios

Documento enfocado en **cómo se comunican** los componentes, **cómo correrlos**
(Docker y local) y **qué variables van en el `.env`**.

---

## 1. Mapa de componentes

```
┌──────────────────────────────────────────────────────────────────┐
│                        HOST (tu máquina / EC2)                   │
│                                                                  │
│   Navegador  ──HTTP──►  :8080  (GATEWAY_HOST_PORT)               │
│                           │                                      │
│  ┌────────────────────────┼─────────────────────────────────┐    │
│  │  Red Docker: "backend" (bridge, aislada)                 │    │
│  │                        ▼                                 │    │
│  │           ┌────────────────────────┐                     │    │
│  │           │      api-gateway       │ :8080               │    │
│  │           │  (Spring Cloud Gateway)│                     │    │
│  │           │  - Valida JWT          │                     │    │
│  │           │  - Enruta por path     │                     │    │
│  │           │  - Propaga headers     │                     │    │
│  │           │    X-User-Email/Role   │                     │    │
│  │           └───────┬───────┬────────┘                     │    │
│  │                   │       │                              │    │
│  │      /api/auth/** │       │ /api/products/**             │    │
│  │      /api/users/**│       │                              │    │
│  │                   ▼       ▼                              │    │
│  │       ┌───────────────┐  ┌──────────────────┐            │    │
│  │       │ auth-service  │  │ product-service  │            │    │
│  │       │    :8081      │  │     :8082        │            │    │
│  │       │ JPA + Security│  │ JPA + Security + │            │    │
│  │       │ + JWT signer  │  │ Spring Batch     │            │    │
│  │       └───────┬───────┘  └────────┬─────────┘            │    │
│  │               │                   │                      │    │
│  │               │  JDBC             │  JDBC                │    │
│  │               ▼                   ▼                      │    │
│  │           ┌──────────────────────────┐                   │    │
│  │           │         MySQL 8          │ :3306             │    │
│  │           │  - auth_db  (tabla users)│                   │    │
│  │           │  - product_db (products, │                   │    │
│  │           │    BATCH_* tablas Spring │                   │    │
│  │           │    Batch)                │                   │    │
│  │           └──────────────────────────┘                   │    │
│  └──────────────────────────────────────────────────────────┘    │
│                                                                  │
│   Host también expone :3306 (MYSQL_HOST_PORT) para conectar con  │
│   DBeaver/MySQL Workbench durante desarrollo.                    │
└──────────────────────────────────────────────────────────────────┘
```

## 2. Quién habla con quién y por qué

| Origen | Destino | Protocolo | URL interna | Motivo |
|---|---|---|---|---|
| Navegador / Angular | **api-gateway** | HTTP | `http://localhost:8080` | Único punto de entrada pública. |
| api-gateway | auth-service | HTTP | `http://auth-service:8081` (DNS interno de Docker) | Enrutar `/api/auth/**` y `/api/users/**`. |
| api-gateway | product-service | HTTP | `http://product-service:8082` | Enrutar `/api/products/**`. |
| auth-service | MySQL | JDBC | `jdbc:mysql://mysql:3306/auth_db` | Persistir usuarios. |
| product-service | MySQL | JDBC | `jdbc:mysql://mysql:3306/product_db` | Persistir productos + tablas `BATCH_*` que Spring Batch crea solo. |
| auth-service ↔ product-service | **no hablan entre sí** | — | — | Comparten el **secreto JWT**, no se llaman directamente. El token emitido por auth es verificado por product e api-gateway usando el mismo secreto. Esto desacopla los servicios. |

> Docker Compose crea una red `backend` (bridge). Dentro de esa red, cada
> contenedor es resoluble por su `container_name` (DNS embebido). Por eso
> `mysql`, `auth-service`, `product-service` funcionan como hostnames.

## 3. Flujo de un request (ejemplo: crear un producto)

```
[1] Usuario hace login en el frontend
    POST http://localhost:8080/api/auth/login
       │
       ▼
[2] api-gateway ve que /api/auth/login está en public-paths → pasa sin JWT
       │
       ▼
[3] auth-service valida credenciales, firma JWT (HS256 + JWT_SECRET),
    devuelve { token, user }
       │
       ▼
[4] Frontend guarda el token en localStorage. Cada request sale con
    Authorization: Bearer <token>  (lo pega el authInterceptor)
       │
       ▼
[5] POST http://localhost:8080/api/products
       │
       ▼
[6] api-gateway
      - Verifica firma del JWT con JWT_SECRET
      - Lee claims (subject=email, role=ADMIN)
      - Añade headers X-User-Email, X-User-Role
      - Enruta a http://product-service:8082
       │
       ▼
[7] product-service
      - Su JwtAuthFilter vuelve a verificar el JWT (defensa en profundidad)
      - Spring Security comprueba @authorizeHttpRequests: requiere ROLE_ADMIN
      - ProductController → ProductServiceImpl → ProductRepository (JDBC/JPA)
      - Inserta en MySQL, responde 201 Created
       │
       ▼
[8] Respuesta viaja de vuelta: product-service → gateway → frontend
```

### Import CSV (Spring Batch) — flujo específico

```
Frontend ──multipart── POST /api/products/batch/import ──► gateway ──► product-service
                                                                         │
                                                                         ▼
                                               BatchController.importCsv()
                                                          │
                                                          ▼
                                            BatchJobServiceImpl:
                                              - guarda archivo en /tmp/product-batch
                                              - JobLauncher.run(importProductsJob, params)
                                                          │
                                                          ▼
                                       Step: chunk(100)  ── reader (FlatFile CSV)
                                                         │
                                                         ▼
                                                        processor (valida, filtra SKU duplicados)
                                                         │
                                                         ▼
                                                        writer (JpaItemWriter → INSERT)
                                                         │
                                                         ▼
                                              Escribe metadatos en tablas BATCH_JOB_EXECUTION,
                                              BATCH_STEP_EXECUTION, etc. (MySQL)

Mientras tanto el frontend consulta estado cada 1.5s:
      GET /api/products/batch/{jobExecutionId}  (polling RxJS con interval + switchMap + takeWhile)
```

## 4. Cómo correrlos

### Opción A — Docker Compose (recomendada para desarrollo y AWS)

```bash
cd backend
cp .env.example .env        # edita JWT_SECRET, MYSQL_ROOT_PASSWORD
docker compose up --build
```

Primera vez tarda 2–5 min (descarga imágenes Maven + compila 3 servicios).
Siguientes arranques: ~30s.

Verificar:
```bash
# Gateway responde
curl http://localhost:8080/actuator/health

# auth directamente (solo expuesto si haces port-forward; por defecto NO)
docker compose exec auth-service curl -s http://localhost:8081/actuator/health
```

Parar:
```bash
docker compose down            # conserva datos MySQL
docker compose down -v         # también borra el volumen MySQL
```

Ver logs de un servicio:
```bash
docker compose logs -f product-service
```

### Opción B — Maven local (útil para debuggear con breakpoints)

Útil cuando quieres atacar un servicio con IntelliJ/VSCode. Los otros dos y
MySQL pueden seguir en Docker.

```bash
# 1. Levanta solo MySQL
cd backend
docker compose up -d mysql

# 2. Arranca cada servicio en una terminal distinta
cd backend/auth-service
./mvnw spring-boot:run

cd backend/product-service
./mvnw spring-boot:run

cd backend/api-gateway
./mvnw spring-boot:run
```

Cada servicio lee su `application.yml` que apunta por defecto a
`jdbc:mysql://localhost:3306/...`. Si tu MySQL local usa otra password,
exporta las variables antes de arrancar:

```bash
export SPRING_DATASOURCE_PASSWORD=tu-password
export APP_JWT_SECRET=el-mismo-secreto-en-los-3-servicios
./mvnw spring-boot:run
```

> En esta modalidad el gateway apunta a `http://localhost:8081` y
> `http://localhost:8082` (defaults del `application.yml`), no a los
> hostnames Docker. Funciona sin cambios.

### Opción C — AWS (ECS Fargate, producción)

1. Push de imágenes a ECR: `docker build -t <ecr>/auth-service auth-service/` (igual para los otros 2).
2. Crea una **Task Definition** por servicio con las mismas env vars del `docker-compose` (`SPRING_DATASOURCE_URL`, `APP_JWT_SECRET`, etc.).
3. `JWT_SECRET` y `MYSQL_ROOT_PASSWORD` → **AWS Secrets Manager**, referenciados en la Task Definition con `secrets:`.
4. `SPRING_DATASOURCE_URL` apunta a **RDS MySQL** (no al contenedor).
5. Un **ALB** delante del gateway (target group en puerto 8080, healthcheck `/actuator/health`).
6. Los 3 servicios en el mismo VPC / service discovery (AWS Cloud Map) para que el gateway resuelva `auth-service.local` y `product-service.local`.

## 5. Qué poner en tu `.env`

El archivo `.env` vive en `backend/.env` (mismo nivel que `docker-compose.yml`)
y docker-compose lo lee automáticamente.

| Variable | Obligatoria | Default | Qué hace |
|---|---|---|---|
| `JWT_SECRET` | **Sí en prod** | un string placeholder | HMAC key usada para firmar JWT en auth-service y validarlo en product-service y api-gateway. **Debe ser el mismo en los 3**. Mínimo 256 bits (~32 chars). |
| `JWT_EXPIRATION_MS` | No | `3600000` (1 h) | Duración del token. |
| `MYSQL_ROOT_PASSWORD` | **Sí en prod** | `rootpass` | Password del usuario MySQL usado por los 3 servicios. |
| `MYSQL_USER` | No | `root` | Usuario de conexión. En producción crea uno por servicio con GRANT acotado. |
| `MYSQL_HOST_PORT` | No | `3306` | Puerto en el HOST donde se expone MySQL (para conectarte con DBeaver). |
| `GATEWAY_HOST_PORT` | No | `8080` | Puerto público del gateway. |

Ejemplo de `.env` de desarrollo:
```env
JWT_SECRET=dev-secret-bastante-largo-para-cumplir-256-bits-minimo-xxxxxxxxxxxxxxxx
JWT_EXPIRATION_MS=3600000
MYSQL_ROOT_PASSWORD=rootpass
MYSQL_USER=root
MYSQL_HOST_PORT=3306
GATEWAY_HOST_PORT=8080
```

Ejemplo de `.env` de producción:
```env
# Genera con: openssl rand -base64 48
JWT_SECRET=c2lnbl9tZV9pbl9wcm9kdWN0aW9uX3dpdGhfc29tZXRoaW5nX3JlYWxseV9sb25nXw==
JWT_EXPIRATION_MS=900000
MYSQL_ROOT_PASSWORD=un-password-fuerte-generado
MYSQL_USER=app_user
MYSQL_HOST_PORT=3306
GATEWAY_HOST_PORT=8080
```

> **Nunca** comitees `.env` al repo. El `.env.example` sí se comitea — es la
> plantilla sin secretos reales. Asegúrate de que `.env` esté en tu
> `.gitignore`.

## 6. Troubleshooting común

| Síntoma | Causa probable | Solución |
|---|---|---|
| `Connection refused` de auth/product al arrancar | MySQL aún no está listo | `depends_on.condition: service_healthy` ya lo maneja. Si persiste, `docker compose logs mysql`. |
| `401 Unauthorized` en product-service | `JWT_SECRET` distinto entre servicios | Verifica que los 3 contenedores tienen la misma env var. |
| `Access denied` a MySQL | Cambiaste `MYSQL_ROOT_PASSWORD` con volumen ya creado | `docker compose down -v` para recrear la DB con la nueva pass (⚠ borra datos). |
| Gateway da `503` al llamar a un servicio | El servicio aún arranca (~30s con Spring Boot) | Espera o sube el `start-period` del healthcheck. |
| CORS error en el navegador | Origin del frontend ≠ `http://localhost:4200` | Ajusta `CorsConfiguration` en `SecurityConfig.java` (auth y product) y `application.yml` del gateway. |
| Spring Batch falla con `Table BATCH_JOB_INSTANCE doesn't exist` | `spring.batch.jdbc.initialize-schema` no está en `always` | Ya configurado en `product-service/application.yml`. |
