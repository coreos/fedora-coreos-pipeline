# next-devel stream status

![next-devel status](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/coreos/fedora-coreos-pipeline/main/next-devel/badge.json)

During the stabilization process for the next major Fedora release, the
Fedora CoreOS `next` stream tracks the upcoming Fedora release.  During
this period, the `next-devel` development stream serves as the upstream
for `next`.

At other times, `next` ships the same content as the `testing` stream.
During these periods, `next` releases are cut directly from `testing-devel`,
and the `next-devel` stream is unmaintained.

This directory contains metadata that tooling can use to determine whether
the `next-devel` stream is currently active.

## Enabling/disabling next-devel

Run `./manage.py {enable|disable}`, then PR the result.

## Available metadata

- [status.json](https://raw.githubusercontent.com/coreos/fedora-coreos-pipeline/main/next-devel/status.json) - whether `next-devel` is currently enabled
- [badge.json](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/coreos/fedora-coreos-pipeline/main/next-devel/badge.json) - endpoint for shields.io badge showing whether `next-devel` is enabled
- [config.yaml](https://raw.githubusercontent.com/coreos/fedora-coreos-pipeline/main/config.yaml) - includes `next-devel` in the `streams` dictionary when the stream is enabled
