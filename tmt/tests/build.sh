#!/bin/bash
set -eo pipefail
set -x

source "$HOME/utils.sh"

mkdir -p "$COSA_DIR"
cosa init --force "${TESTING_FARM_GIT_URL}" --branch "${TESTING_FARM_GIT_REF}"
cosa import "docker://$IMAGE_URL"
CONFIG_COMMIT=$(jq -r ".\"coreos-assembler.oci-imported-labels\".\"vcs-ref\"" "${COSA_DIR}/builds/latest/$(arch)/meta.json")
pushd "${COSA_DIR}/src/config"
git config --global --add safe.directory "${COSA_DIR}/src/config"
git checkout "$CONFIG_COMMIT"
popd

if [ "$TEST_CASE" = "build-qemu" ]; then
    cosa osbuild qemu
elif [ "$TEST_CASE" = "build-iso" ]; then
    cosa osbuild live metal metal4k
fi
