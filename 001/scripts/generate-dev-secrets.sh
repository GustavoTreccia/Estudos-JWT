#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
if [ -e .env ] || [ -e .local ]; then
    printf '%s\n' 'Recusando sobrescrever .env ou .local existentes.' >&2
    exit 1
fi
umask 077
mkdir .local
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out .local/private.pem
openssl pkey -in .local/private.pem -pubout -out .local/public.pem
printf 'DB_PASSWORD=%s\nOAUTH_CLIENT_SECRET=%s\nVERIFICATION_CODE_SECRET=%s\nLOCAL_UID=%s\nLOCAL_GID=%s\n' \
    "$(openssl rand -hex 32)" "$(openssl rand -hex 32)" "$(openssl rand -hex 32)" \
    "$(id -u)" "$(id -g)" > .env
printf '%s\n' 'Segredos locais gerados com permissoes restritas ao usuario atual.'
