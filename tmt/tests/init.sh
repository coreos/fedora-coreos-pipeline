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
# print commit message
git clone "${CONFIG_GIT_URL}" /tmp/git-repo
pushd /tmp/git-repo
git checkout "${CONFIG_GIT_REF}"
echo "git commit message: $(git log --format=%B -n 1 HEAD)"
popd

mkdir -p "$COSA_DIR"
cosa init --force "${CONFIG_GIT_URL}" --branch "${CONFIG_GIT_REF}" --commit "${CONFIG_COMMIT}"
cosa import "docker://$IMAGE_URL"
pushd "${COSA_DIR}/src/config"
git config --global --add safe.directory "${COSA_DIR}/src/config"
git checkout "$CONFIG_COMMIT"
popd
