#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TERRAFORM_DIR="${ROOT_DIR}/terraform"
TERRAFORM_BIN="${TERRAFORM_BIN:-terraform}"

if ! command -v "${TERRAFORM_BIN}" >/dev/null 2>&1; then
  echo "❌ Terraform não encontrado. Instale Terraform 1.6+ ou defina TERRAFORM_BIN."
  exit 1
fi

cd "${TERRAFORM_DIR}"

"${TERRAFORM_BIN}" fmt -check -recursive
echo "✅ Formatação Terraform passou"

"${TERRAFORM_BIN}" init -backend=false -input=false
echo "✅ Inicialização do provider OCI passou"

"${TERRAFORM_BIN}" validate
echo "✅ Validação Terraform passou"
