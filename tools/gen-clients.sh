#!/bin/sh
# Regenerates the TypeScript types in web/src/generated/ from every OpenAPI slice
# (17A section 7, "Generated clients"). Run it through `make gen-clients` after changing
# a slice, and commit the result: CI fails when the generated files are stale.
#
# common.yaml holds shared components only; it has no paths and gets no client of its own.
set -e

cd "$(dirname "$0")/.."

SLICES=backend/app/src/main/resources/openapi
OUT=web/src/generated

for slice in "$SLICES"/*.yaml; do
    name=$(basename "$slice" .yaml)
    [ "$name" = "common" ] && continue
    echo "generating $OUT/$name.ts"
    pnpm --dir web exec openapi-typescript "../$slice" -o "src/generated/$name.ts"
done
