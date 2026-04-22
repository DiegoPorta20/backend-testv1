# Backend – Microservicios

Arquitectura de 3 servicios + gateway, MySQL, JWT, Spring Batch y Docker.

```
Cliente (Angular :4200)
        │
        ▼
┌──────────────────┐
│   api-gateway    │  :8080  (Spring Cloud Gateway, reactivo)
│  valida JWT      │  ──► enruta y añade X-User-Email / X-User-Role
└──────────────────┘
     │           │
     ▼           ▼
┌─────────┐  ┌────────────────┐
│  auth-  │  │ product-       │
│ service │  │ service        │
│  :8081  │  │  :8082 + Batch │
└─────────┘  └────────────────┘
     │           │
     └────► MySQL :3306 (dbs: auth_db, product_db)
```

## Servicios

### auth-service (puerto 8081)
- Registro/login con `BCryptPasswordEncoder(12)` + JWT HS256.
- Roles `USER` / `ADMIN`.
- Endpoints públicos: `POST /api/auth/register`, `POST /api/auth/login`.
- Autenticados: `GET /api/auth/me`, `GET /api/users` (solo `ADMIN`).

### product-service (puerto 8082)
- CRUD de productos con paginación + búsqueda.
- `POST /api/products/batch/import` (multipart `.csv`) dispara un job de
  Spring Batch con chunks de 100, tolerante a fallos (skipLimit 50).
- `GET /api/products/batch/{jobExecutionId}` para consultar el estado.
- Solo `ADMIN` puede crear/editar/eliminar/importar; cualquier autenticado lee.

### api-gateway (puerto 8080)
- Enruta `/api/auth/**` → auth-service, `/api/products/**` → product-service.
- `JwtAuthenticationGatewayFilter` (GlobalFilter) verifica el token y
  propaga `X-User-Email` y `X-User-Role` a los microservicios.
- Paths públicos configurables en `application.yml` (`app.security.public-paths`).
- CORS centralizado para `http://localhost:4200`.

## Buenas prácticas aplicadas

- **Estructura MVC + capas**: `controller` → `service` (interface) → `service.impl`
  → `repository` (interface) → `domain`. `dto` para entrada/salida, `exception`
  para errores controlados, `security` para filtros/JWT, `config` para beans.
- **Interfaces en repositorios y servicios**: acoplamiento bajo, facilita tests.
- **DTOs (records)** separados de entidades JPA — nunca se expone `User.password`.
- **Validación** con `@Valid` + Jakarta Bean Validation, mensajes de error
  estructurados en `GlobalExceptionHandler`.
- **JWT stateless** (sin sesiones), claims firmados HS256, secreto vía env var.
- **BCrypt strength 12** para passwords.
- **CORS explícito** por servicio y en el gateway.
- **Spring Security** con `SessionCreationPolicy.STATELESS` y
  `authorizeHttpRequests` por endpoint + rol.
- **Spring Batch** con job idempotente (skip de SKUs existentes, parámetros
  únicos por timestamp) y manejo tolerante a filas corruptas (skipLimit).
- **Actuator `/health`** expuesto para healthchecks de Docker/ELB/ALB.
- **Dockerfiles multi-stage** con usuario no-root y flags JVM
  (`UseContainerSupport`, `MaxRAMPercentage=75`) para contenedores.

## Correr localmente

```bash
cd backend
cp .env.example .env        # ajusta JWT_SECRET
docker compose up --build
```

Servicios:
- Gateway: http://localhost:8080
- auth: http://localhost:8081 (directo para desarrollo)
- product: http://localhost:8082 (directo para desarrollo)
- MySQL: localhost:3306 (user `root`, pass `rootpass`)

### Smoke test

```bash
# Registrar
curl -X POST http://localhost:8080/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"name":"Admin","email":"admin@test.com","password":"secret12"}'

# Login (copia el token de la respuesta)
curl -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@test.com","password":"secret12"}'

# Listar productos
curl http://localhost:8080/api/products \
  -H "Authorization: Bearer <TOKEN>"
```

> Para probar endpoints de ADMIN en desarrollo puedes actualizar manualmente
> el rol en MySQL: `UPDATE auth_db.users SET role='ADMIN' WHERE email='admin@test.com';`
> En producción deberías tener un endpoint/CLI para promocionar usuarios.

## Despliegue en AWS

Las imágenes generadas por `docker compose build` son deployables sin cambios a:

- **ECS Fargate** (recomendado): una Task Definition por servicio, un ALB
  apuntando al gateway en puerto 8080. RDS MySQL como datasource.
  Secreto JWT en AWS Secrets Manager inyectado como env var.
- **EKS**: tres `Deployment` + `Service`, `Ingress` al gateway.
  Secrets en Kubernetes o External Secrets Operator.
- **EC2 + docker-compose**: válido para entornos pequeños; el `docker-compose.yml`
  funciona tal cual, apuntando a una RDS en vez del contenedor MySQL local.

Variables de entorno clave en producción:
- `JWT_SECRET` (Secrets Manager / SSM Parameter Store)
- `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`
- `APP_AUTH_SERVICE_URL`, `APP_PRODUCT_SERVICE_URL` (en el gateway)

Healthchecks `/actuator/health` ya están expuestos — úsalos en los Target
Groups del ALB y en las health probes de ECS/EKS.

## CSV de ejemplo

`product-service/src/main/resources/sample-products.csv` tiene 5 filas válidas
para probar el import. Desde el frontend: `/products/import`.
