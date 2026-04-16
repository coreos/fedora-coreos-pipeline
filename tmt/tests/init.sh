#!/bin/bash
export COREOS_ASSEMBLER_CONTAINER_LATEST="quay.io/coreos-assembler/coreos-assembler:latest"

cat <<'EOF' > "$HOME/utils.sh"
export COSA_DIR=$HOME/workspace/build
cosa ()
{
    export COREOS_ASSEMBLER_CONTAINER_LATEST="quay.io/coreos-assembler/coreos-assembler:latest"
    set -x;
    podman run --rm --security-opt=label=disable --privileged \
    -v "${COSA_DIR}:/srv" --device=/dev/kvm \
    --device=/dev/fuse --tmpfs=/tmp -v /var/tmp:/var/tmp --name=cosa "${COREOS_ASSEMBLER_CONTAINER_LATEST}" "$@";
}
collect_kola_artifacts() {
    mkdir -p "$TMT_TEST_DATA"
    cd $COSA_DIR && tar -C "$OUTPUT_DIR" -c --xz "$KOLA_ID" > "$KOLA_ID-$TOKEN.tar.xz"
    cd $COSA_DIR && mv "$KOLA_ID-$TOKEN.tar.xz" "$TMT_TEST_DATA/$KOLA_ID-$TOKEN.tar.xz"
}
run_kola(){
    OUTPUT_DIR=$(cd $COSA_DIR && cosa shell -- mktemp -d tmp/kola-XXXX)
    TOKEN=$(uuidgen | cut -f1 -d -)
    KOLA_ID=${KOLA_ID:-kola}
    cd $COSA_DIR && cosa kola "$KOLA_ACTION" --build=latest --arch=$(arch) --output-dir="$OUTPUT_DIR/$KOLA_ID" $KOLA_EXTRA_ARGS
}
EOF

echo "cosa container: $COREOS_ASSEMBLER_CONTAINER_LATEST"
echo "arch: $(arch)"
echo "using image: $IMAGE_URL"
skopeo inspect "docker://$IMAGE_URL" > meta.json
GIT_URL=$(cat meta.json | jq -r ".Labels.\"org.opencontainers.image.source\"")
GIT_REF=$(cat meta.json | jq -r ".Labels.\"com.coreos.stream\"")
echo "export GIT_URL=${GIT_URL}" >> "$HOME/utils.sh"
echo "export GIT_REF=${GIT_REF}" >> "$HOME/utils.sh"
echo "git version: $(git --version)"
echo "git url: ${GIT_URL}"
echo "git branch: ${GIT_REF}"
git clone "${GIT_URL}" /tmp/git-repo
pushd /tmp/git-repo
git checkout "${GIT_REF}"
echo "git commit message: $(git log --format=%B -n 1 HEAD)"
