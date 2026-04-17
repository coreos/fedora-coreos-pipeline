#!/bin/bash
export COREOS_ASSEMBLER_CONTAINER_LATEST="quay.io/coreos-assembler/coreos-assembler:latest"

echo "cosa container: $COREOS_ASSEMBLER_CONTAINER_LATEST"
echo "arch: $(arch)"
echo "using image: $IMAGE_URL"
skopeo inspect "docker://$IMAGE_URL" > meta.json
CONFIG_COMMIT=$(jq -r ".Labels.\"vcs-ref\"" meta.json)
CONFIG_GIT_URL=$(jq -r ".Labels.\"org.opencontainers.image.source\"" meta.json)
CONFIG_GIT_REF=$(jq -r ".Labels.\"com.coreos.stream\"" meta.json)

echo "export CONFIG_COMMIT=${CONFIG_COMMIT}" >> "utils.sh"
echo "export CONFIG_GIT_URL=${CONFIG_GIT_URL}" >> "utils.sh"
echo "export CONFIG_GIT_REF=${CONFIG_GIT_REF}" >> "utils.sh"

echo "git version: $(git --version)"
echo "git url: ${GIT_URL}"
echo "git branch: ${GIT_REF}"
git clone "${GIT_URL}" /tmp/git-repo
pushd /tmp/git-repo
git checkout "${GIT_REF}"
echo "git commit message: $(git log --format=%B -n 1 HEAD)"

source "utils.sh"

mkdir -p "$COSA_DIR"
cosa init --force "${CONFIG_GIT_URL}" --branch "${CONFIG_GIT_REF}" --commit "${CONFIG_COMMIT}"
cosa import "docker://$IMAGE_URL"
pushd "${COSA_DIR}/src/config"
git config --global --add safe.directory "${COSA_DIR}/src/config"
git checkout "$CONFIG_COMMIT"
popd
