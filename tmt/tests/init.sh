#!/bin/bash
set -eEuo pipefail
set -x

source "utils.sh"

echo "cosa container: $COREOS_ASSEMBLER_CONTAINER_LATEST"
echo "arch: $(arch)"
echo "using image: $IMAGE_URL"

skopeo inspect "docker://$IMAGE_URL" > meta.json
CONFIG_COMMIT=$(jq -r ".Labels.\"vcs-ref\"" meta.json)
CONFIG_GIT_URL=$(jq -r ".Labels.\"org.opencontainers.image.source\"" meta.json)
CONFIG_GIT_REF=$(jq -r ".Labels.\"com.coreos.stream\"" meta.json)

echo "git version: $(git --version)"
echo "git url: ${CONFIG_GIT_URL}"
echo "git branch: ${CONFIG_GIT_REF}"

mkdir -p "$COSA_DIR"
cosa init --force "${CONFIG_GIT_URL}" --branch "${CONFIG_GIT_REF}" --commit "${CONFIG_COMMIT}"
cosa import "docker://$IMAGE_URL"
