#!/usr/bin/env bash
# Script de despliegue ejecutado en el EC2.
# Uso (manual): ./deploy.sh
# Uso (GitHub Actions): ver .github/workflows/deploy.yml
set -euo pipefail

cd "$(dirname "$0")"

echo "==> Pull de la rama actual"
git pull --ff-only

echo "==> Build de imágenes Docker"
docker compose -f docker-compose.prod.yml --env-file .env.prod build

echo "==> Levantando contenedores"
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d

echo "==> Limpieza de imágenes sin uso"
docker image prune -f

echo "==> Estado"
docker compose -f docker-compose.prod.yml ps
