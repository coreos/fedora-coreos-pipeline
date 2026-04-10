#!/bin/bash
set -eEuo pipefail
set -x

source $HOME/utils.sh
trap collect_kola_artifacts ERR

if [ "$TEST_CASE" = "test-qemu" ]; then
    KOLA_ACTION="run"
    KOLA_ID="kola"
    KOLA_EXTRA_ARGS="--rerun --allow-rerun-success=tags=needs-internet --on-warn-failure-exit-77 --tag=!reprovision --parallel=5"
    run_kola
    collect_kola_artifacts

    # reprovision test
    KOLA_ACTION="run"
    KOLA_ID="kola-reprovision"
    KOLA_EXTRA_ARGS="--tag=reprovision"
    run_kola
    collect_kola_artifacts

elif [ "$TEST_CASE" = "test-kola-upgrade" ]; then
    # upgrade test
    KOLA_ACTION="run-upgrade"
    KOLA_ID="run-upgrade"
    KOLA_EXTRA_ARGS="--upgrades"
    run_kola
    collect_kola_artifacts

elif [ "$TEST_CASE" = "test-iso" ]; then
    KOLA_ACTION="testiso"
    KOLA_ID="test-iso"
    KOLA_EXTRA_ARGS="--inst-insecure"
    run_kola
    collect_kola_artifacts
fi
