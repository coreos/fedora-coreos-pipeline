#!/bin/bash
set -eEuo pipefail
set -x

source "utils.sh"

echo "cosa container: $COREOS_ASSEMBLER_CONTAINER_REF"
echo "arch: $(arch)"
echo "using image: $IMAGE_URL"

skopeo inspect -n "docker://$IMAGE_URL" > manifest.json
CONFIG_COMMIT=$(jq -r '.Labels."vcs-ref"' manifest.json)
CONFIG_GIT_URL=$(jq -r '.Labels."org.opencontainers.image.source"' manifest.json)
CONFIG_GIT_REF=$(jq -r '.Labels."com.coreos.stream"' manifest.json)

echo "git version: $(git --version)"
echo "git url: ${CONFIG_GIT_URL}"
echo "git branch: ${CONFIG_GIT_REF}"

mkdir -p "$COSA_DIR"
cosa init --force "${CONFIG_GIT_URL}" --branch "${CONFIG_GIT_REF}" --commit "${CONFIG_COMMIT}"

# Import the container image.
# For import plans we also download the pre-built qemu image.
import_args=()
if [[ "${TMT_PLAN_DATA}" == *-import/* ]]; then
  import_args+=(--download qemu)
fi
cosa import "docker://$IMAGE_URL" "${import_args[@]}"
