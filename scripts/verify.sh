#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"

echo "==> Verificando compilação e 10 cenários automatizados"

if [[ -x "./mvnw" ]]; then
  ./mvnw -B clean test
elif command -v mvn >/dev/null 2>&1; then
  mvn -B clean test
elif command -v docker >/dev/null 2>&1; then
  docker run --rm \
    -v "$project_dir:/workspace" \
    -w /workspace \
    maven:3.9.11-eclipse-temurin-17 \
    mvn -B clean test
else
  echo "ERRO: instale Maven 3.9+ ou Docker para executar a verificação." >&2
  exit 1
fi

echo
echo "✅ Compilação Java passou"
echo "✅ Suíte completa com 10 cenários passou"
