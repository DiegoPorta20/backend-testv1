# Guia de despliegue en produccion (EC2 + RDS + GitHub Actions)

Este documento explica como desplegar el backend en un EC2 con MySQL en RDS, y como automatizar el deploy con GitHub Actions en cada `push` a `main`.

---

## Arquitectura de deploy

```
  GitHub (push a main)
        |
        v
  GitHub Actions (runner) --SSH--> EC2 (ubuntu@18.220.81.84)
                                    |
                                    |-- docker compose (api-gateway, auth-service, product-service)
                                    |
                                    +---TCP:3306---> RDS MySQL
                                                     (database-1.xxxxx.us-east-2.rds.amazonaws.com)
```

- **EC2**: corre los 3 microservicios via `docker compose`.
- **RDS**: MySQL gestionado (NO en contenedor). Privado dentro de la VPC.
- **GitHub Actions**: al hacer `push`, entra por SSH al EC2, hace `git pull`, regenera el `.env` desde los GitHub Secrets, reconstruye y reinicia los contenedores.

---

## 1. Preparacion inicial de la RDS

Los microservicios esperan **dos bases de datos**: `auth_db` y `product_db`. Hay que crearlas (la RDS solo vino con la base por defecto `mysql`).

Desde tu EC2 o desde DataGrip con tunel SSH:

```sql
CREATE DATABASE IF NOT EXISTS auth_db
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE DATABASE IF NOT EXISTS product_db
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

> Equivale a lo que hacia `mysql/init.sql` en local. En RDS se corre una sola vez manualmente.

### Security Group de la RDS

En la consola de RDS -> tu base -> **Connectivity & security** -> clic en el VPC security group -> **Inbound rules** -> agrega:

| Type | Port | Source |
|---|---|---|
| MYSQL/Aurora | 3306 | Security Group del EC2 |

Esto permite que solo el EC2 (y cualquier maquina dentro de ese SG) pueda hablar con la RDS. **Nunca** `0.0.0.0/0`.

---

## 2. Variables de entorno

### Que variables existen y para que

| Variable | Descripcion | Ejemplo |
|---|---|---|
| `DB_HOST` | Endpoint de la RDS | `database-1.xxxxx.us-east-2.rds.amazonaws.com` |
| `DB_PORT` | Puerto MySQL | `3306` |
| `DB_USER` | Usuario maestro de la RDS | `admin` |
| `DB_PASSWORD` | Password del usuario maestro | *(secreto)* |
| `JWT_SECRET` | Clave HMAC para firmar tokens (debe ser la misma en los 3 servicios) | *(secreto, min 256 bits)* |
| `JWT_EXPIRATION_MS` | Duracion del token en ms | `3600000` (1h) |
| `GATEWAY_HOST_PORT` | Puerto expuesto al exterior por el api-gateway | `80` |

Generar un `JWT_SECRET` seguro:

```bash
openssl rand -base64 48
```

### Donde viven las variables

Hay **dos lugares**:

1. **En el EC2**, como archivo `.env` junto al `docker-compose.prod.yml`. Docker Compose lo lee automaticamente.
2. **En GitHub Secrets**, para que el workflow pueda regenerar ese `.env` en cada deploy.

Ambos deben estar sincronizados. El workflow **sobreescribe** el `.env` del servidor con lo que haya en Secrets, asi que la fuente de verdad es GitHub Secrets.

### .env.prod.example (plantilla)

En el repo hay un `.env.prod.example` con la plantilla. Para el **primer deploy manual**:

```bash
# En el EC2
cd ~/backend
cp .env.prod.example .env
nano .env
# Rellena los valores reales
chmod 600 .env
```

---

## 3. Primer deploy manual (una sola vez)

Antes de configurar GitHub Actions, conviene hacer un deploy a mano para verificar que todo arranca:

```bash
# En el EC2
cd ~/backend

# Crear el .env con valores reales (ver paso anterior)

# Levantar los servicios (usando el compose de prod, sin mysql local)
docker compose -f docker-compose.prod.yml up -d --build

# Verificar estado
docker compose -f docker-compose.prod.yml ps
docker compose -f docker-compose.prod.yml logs -f api-gateway
```

Prueba desde tu PC:

```bash
curl http://18.220.81.84/actuator/health
```

Si responde, el backend esta vivo. Si falla, revisa logs:

```bash
docker compose -f docker-compose.prod.yml logs auth-service | tail -50
```

Errores tipicos y causas:

| Error en logs | Causa | Solucion |
|---|---|---|
| `Communications link failure` | EC2 no llega a la RDS | Revisa Security Group de la RDS |
| `Access denied for user 'admin'` | Password incorrecto | Revisa `.env` |
| `Unknown database 'auth_db'` | Falta crear la DB | Ejecutar el SQL del paso 1 |
| `Port 80 already in use` | Otro proceso ocupa el 80 | `sudo lsof -i :80` y detenerlo, o cambiar `GATEWAY_HOST_PORT` |

---

## 4. Configurar GitHub Actions

El workflow esta en `.github/workflows/deploy.yml`. Se dispara en cada push a `main`.

### Secretos que hay que crear

En GitHub: repo -> **Settings** -> **Secrets and variables** -> **Actions** -> **New repository secret**. Crea estos secretos:

#### Acceso al EC2

| Secret | Valor |
|---|---|
| `EC2_HOST` | `18.220.81.84` (IP publica de tu EC2) |
| `EC2_USER` | `ubuntu` |
| `EC2_SSH_KEY` | Contenido **completo** del `.pem` (incluyendo `-----BEGIN...` y `-----END...`) |

Para obtener el contenido del `.pem`:

```powershell
Get-Content "C:\Users\diego\OneDrive\Documentos\keyaws\TestV1.pem"
```

Copia TODO lo que imprima y pegalo como valor del secret `EC2_SSH_KEY`.

#### Configuracion del deploy

| Secret | Valor | Nota |
|---|---|---|
| `REPO_PATH` | `/home/ubuntu/backend` | Ruta del repo clonado en el EC2 |
| `REPO_BRANCH` | `main` | Rama a desplegar |

#### Variables de la aplicacion

| Secret | Valor |
|---|---|
| `DB_HOST` | `database-1.xxxxx.us-east-2.rds.amazonaws.com` |
| `DB_PORT` | `3306` |
| `DB_USER` | `admin` |
| `DB_PASSWORD` | *(el password NUEVO de RDS)* |
| `JWT_SECRET` | *(generado con `openssl rand -base64 48`)* |
| `JWT_EXPIRATION_MS` | `3600000` |
| `GATEWAY_HOST_PORT` | `80` |

> Recomendacion: el `JWT_SECRET` debe ser **diferente** al que uses en desarrollo. Un secreto por entorno.

### Requisitos en el EC2 (una sola vez)

Para que el workflow funcione, en el EC2:

1. El repo debe estar clonado en `REPO_PATH` (por defecto `/home/ubuntu/backend`).
2. `git` configurado para poder hacer `pull` sin pedir credenciales:
   - Si el repo es publico: ya funciona.
   - Si es privado: configura un **deploy key** o un **PAT** en el remote (`git remote set-url origin https://<user>:<token>@github.com/...`).
3. Docker y docker compose instalados, y el usuario `ubuntu` en el grupo `docker`.

### Disparar el deploy

- **Automatico**: haces `git push origin main` y el workflow corre.
- **Manual**: en GitHub -> pestana **Actions** -> "Deploy Backend to EC2" -> **Run workflow**.

Veras los logs en tiempo real. Si falla, el paso que falla queda en rojo con el output.

---

## 5. Flujo de un deploy (que pasa por dentro)

1. Push a `main` dispara el workflow.
2. Runner de GitHub se conecta por SSH al EC2 con `EC2_SSH_KEY`.
3. En el EC2:
   - `cd` al repo.
   - `git fetch --all && git reset --hard origin/main` (actualiza el codigo, descarta cambios locales por si acaso).
   - Regenera `.env` usando las variables pasadas desde GitHub Secrets.
   - `docker compose -f docker-compose.prod.yml up -d --build` (reconstruye imagenes y reinicia contenedores).
   - `docker image prune -f` (limpia imagenes viejas sin uso).
   - Imprime el estado final.
4. Workflow termina en verde si todo OK.

> Tiempo aproximado: 3-7 minutos (el build de Maven es lo mas lento).

---

## 6. Archivos del repo relevantes

```
backend/
  docker-compose.yml            # Local dev (con MySQL en contenedor)
  docker-compose.prod.yml       # Produccion (usa RDS, sin contenedor MySQL)
  .env.example                  # Plantilla para desarrollo local
  .env.prod.example             # Plantilla para produccion
  .env                          # REAL (NO commitear)
  .github/workflows/deploy.yml  # CI/CD
  DEPLOY.md                     # Este archivo
```

Asegurate de que `.gitignore` incluya:

```
.env
.env.prod
```

Verifica con:

```bash
git check-ignore -v backend/.env
```

Si no aparece como ignorado, anade esas lineas al `.gitignore` del repo.

---

## 7. HTTPS (siguiente paso, opcional pero recomendado)

El frontend esta en CloudFront (HTTPS). El backend en HTTP plano hara que el navegador bloquee las llamadas por mixed content.

Tres opciones:

1. **Behavior en CloudFront**: agrega una ruta `/api/*` que apunte al EC2 como origin. Recomendado.
2. **ALB + ACM**: pones un ALB delante del EC2, con certificado gratuito de ACM.
3. **Nginx + Let's Encrypt en el mismo EC2**: necesitas un dominio apuntando al EC2.

---

## 8. Comandos utiles de operacion

```bash
# Estado de los servicios
docker compose -f docker-compose.prod.yml ps

# Logs de un servicio en vivo
docker compose -f docker-compose.prod.yml logs -f auth-service

# Reiniciar un solo servicio (por ejemplo tras cambiar su config)
docker compose -f docker-compose.prod.yml restart auth-service

# Apagar todo (sin borrar imagenes ni datos)
docker compose -f docker-compose.prod.yml down

# Ver consumo de recursos
docker stats

# Liberar espacio (imagenes, cache, contenedores detenidos)
docker system prune -af
```

---

## 9. Checklist antes de cerrar el dia

- [ ] Password de RDS rotado (el expuesto en chat NO sirve).
- [ ] `.env` en el EC2 tiene permisos `600` y contiene los valores correctos.
- [ ] Los secretos de GitHub Actions estan creados con los valores **nuevos**.
- [ ] `.env` y `.env.prod` estan en `.gitignore`.
- [ ] El EC2 puede conectar a RDS (`mysql -h DB_HOST -u admin -p` funciona).
- [ ] `curl http://IP-EC2/actuator/health` responde OK.
- [ ] El workflow de GitHub Actions pasa en verde.
