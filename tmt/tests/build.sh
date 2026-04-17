#!/bin/bash
set -eo pipefail
set -x

source "utils.sh"

if [ "$TEST_CASE" = "build-qemu" ]; then
    cosa osbuild qemu
elif [ "$TEST_CASE" = "build-iso" ]; then
    cosa osbuild live metal metal4k
fi
