#!/bin/bash

cat ../../builder-common.bu | butane --pretty --strict > builder-common.ign
cat ../../coreos-aarch64-builder.bu | butane --pretty --strict --files-dir=. > coreos-aarch64-builder.ign
