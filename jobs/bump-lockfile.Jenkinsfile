@Library('github.com/coreos/coreos-ci-lib@main') _

def streams, gp
node {
    checkout scm
    streams = load("streams.groovy")
    gp = load("gp.groovy")
}

repo = "coreos/fedora-coreos-config"
botCreds = "github-coreosbot-token"

// Base URL through which to download artifacts
BUILDS_BASE_HTTP_URL = "https://builds.coreos.fedoraproject.org/prod/streams"

properties([
    // we're only triggered by bump-lockfiles
    pipelineTriggers([]),
    parameters([
        choice(name: 'STREAM',
               choices: streams.development,
               description: 'Fedora CoreOS development stream to bump'),
    ])
])

echo "Waiting for bump-${params.STREAM} lock"
currentBuild.description = "[${params.STREAM}] Waiting"

def getLockfileInfo(lockfile) {
    def pkgChecksum, pkgTimestamp
    if (utils.pathExists(lockfile)) {
        pkgChecksum = shwrapCapture("jq -c .packages ${lockfile} | sha256sum")
        pkgTimestamp = shwrapCapture("jq -c .metadata.generated ${lockfile} | xargs -I{} date --date={} +%s") as Integer
    } else {
        // lockfile doesn't exist. Give some braindead, but valid values
        pkgChecksum = ""
        pkgTimestamp = 1
    }
    return [pkgChecksum, pkgTimestamp]
}

try { lock(resource: "bump-${params.STREAM}") { timeout(time: 120, unit: 'MINUTES') { cosaPod {
    currentBuild.description = "[${params.STREAM}] Running"

    // set up git user upfront
    shwrap("""
      git config --global user.name "CoreOS Bot"
      git config --global user.email "coreosbot@fedoraproject.org"
    """)

    def branch = params.STREAM
    def forceTimestamp = false
    def haveChanges = false
    shwrap("cosa init --branch ${branch} https://github.com/${repo}")
    shwrap("cosa buildprep ${BUILDS_BASE_HTTP_URL}/${branch}/builds")

    def lockfile, pkgChecksum, pkgTimestamp
    def archinfo = [x86_64: [:], aarch64: [:]]
    for (arch in archinfo.keySet()) {
        lockfile = "src/config/manifest-lock.${arch}.json"
        (pkgChecksum, pkgTimestamp) = getLockfileInfo(lockfile)
        archinfo[arch]['prevPkgChecksum'] = pkgChecksum
        archinfo[arch]['prevPkgTimestamp'] = pkgTimestamp
    }

    // do a first fetch where we only fetch metadata; no point in
    // importing RPMs if nothing actually changed
    stage("Fetch Metadata") {
        parallel x86_64: {
            shwrap("cosa fetch --update-lockfile --dry-run")
        }, aarch64: {
            def appendFlags = "--git-ref=${params.STREAM}"
            appendFlags += " --git-url=https://github.com/${repo}"
            appendFlags += " --returnFiles=src/config/manifest-lock.aarch64.json"
            gp.gangplankArchWrapper([cmd: "cosa fetch --update-lockfile --dry-run",
                                     arch: "aarch64", appendFlags: appendFlags])
            shwrap("cp builds/cache/src/config/manifest-lock.aarch64.json src/config/manifest-lock.aarch64.json")
        }
    }

    for (arch in archinfo.keySet()) {
        lockfile = "src/config/manifest-lock.${arch}.json"
        (pkgChecksum, pkgTimestamp) = getLockfileInfo(lockfile)
        archinfo[arch]['newPkgChecksum'] = pkgChecksum
        archinfo[arch]['newPkgTimestamp'] = pkgTimestamp

        if (archinfo[arch]['newPkgChecksum'] != archinfo[arch]['prevPkgChecksum']) {
            haveChanges = true
        }
        if ((archinfo[arch]['newPkgTimestamp'] - archinfo[arch]['prevPkgTimestamp']) > (2*24*60*60)) {
            // Let's update the timestamp after two days even if no packages were updated.
            // This will bump the date in the version number for FCOS, which is an indicator
            // of how fresh the package set is.
            println("2 days and no package updates. Pushing anyway to update timestamps.")
            forceTimestamp = true
        }
    }

    if (!haveChanges && !forceTimestamp) {
        currentBuild.result = 'SUCCESS'
        currentBuild.description = "[${params.STREAM}] 💤 (no change)"
        return
    }

    // sanity-check only base lockfiles were changed
    shwrap("""
      # do this separately so set -e kicks in if it fails
      files=\$(git -C src/config ls-files --modified --deleted)
      for f in \${files}; do
        if ! [[ \${f} =~ ^manifest-lock\\.[0-9a-z_]+\\.json ]]; then
          echo "Unexpected modified file \${f}"
          exit 1
        fi
      done
    """)

    // The bulk of the work (build, test, etc) is done in the following.
    // We only need to do that work if we have changes.
    if (haveChanges) {
        parallel "Fetch/Build/Test aarch64": {
            shwrap("""
               cat <<'EOF' > spec.spec
job:
  strict: true
  miniocfgfile: ""
recipe:
  git_ref: ${params.STREAM}
  git_url: https://github.com/${repo}
stages:
- id: ExecOrder 1 Stage
  execution_order: 1
  description: Stage 1 execution base
  build_artifacts: [base]
  post_commands:
    - cosa kola run --basic-qemu-scenarios
    - rm -f builds/builds.json # https://github.com/coreos/coreos-assembler/issues/2317
delay_meta_merge: false
EOF
                   """)
            gp.gangplankArchWrapper([spec: "spec.spec", arch: "aarch64"])
        }, x86_64: {
            stage("Fetch") {
                // XXX: hack around subtle lockfile bug (jlebon to submit an
                // rpm-ostree issue or patch about this)
                shwrap("cp src/config/fedora-coreos-pool.repo{,.bak}")
                shwrap("echo cost=500 >> src/config/fedora-coreos-pool.repo")
                shwrap("cosa fetch --strict")
                shwrap("cp src/config/fedora-coreos-pool.repo{.bak,}")
            }

            stage("Build") {
                shwrap("cosa build --force --strict")
            }

            fcosKola(cosaDir: env.WORKSPACE)

            stage("Build Metal") {
                shwrap("cosa buildextend-metal")
                shwrap("cosa buildextend-metal4k")
            }

            stage("Build Live") {
                shwrap("cosa buildextend-live --fast")
                // Test metal4k with an uncompressed image and metal with a
                // compressed one
                shwrap("cosa compress --artifact=metal")
            }

            try {
                parallel metal: {
                    shwrap("kola testiso -S --scenarios pxe-install,iso-install,iso-offline-install,iso-live-login,iso-as-disk --output-dir tmp/kola-testiso-metal")
                }, metal4k: {
                    shwrap("kola testiso -S --scenarios iso-install,iso-offline-install --qemu-native-4k --output-dir tmp/kola-testiso-metal4k")
                }, uefi: {
                    shwrap("mkdir -p tmp/kola-testiso-uefi")
                    shwrap("kola testiso -S --qemu-firmware=uefi --scenarios iso-live-login,iso-as-disk --output-dir tmp/kola-testiso-uefi/insecure")
                    shwrap("kola testiso -S --qemu-firmware=uefi-secure --scenarios iso-live-login,iso-as-disk --output-dir tmp/kola-testiso-uefi/secure")
                }
            } finally {
                shwrap("tar -cf - tmp/kola-testiso-metal/ | xz -c9 > ${env.WORKSPACE}/kola-testiso-metal.tar.xz")
                shwrap("tar -cf - tmp/kola-testiso-metal4k/ | xz -c9 > ${env.WORKSPACE}/kola-testiso-metal4k.tar.xz")
                shwrap("tar -cf - tmp/kola-testiso-uefi/ | xz -c9 > ${env.WORKSPACE}/kola-testiso-uefi.tar.xz")
                archiveArtifacts allowEmptyArchive: true, artifacts: 'kola-testiso*.tar.xz'
            }
        }
    }

    // OK, we're ready to push: just push to the branch. In the future, we might be
    // fancier here; e.g. if tests fail, just open a PR, or if tests passed but a
    // package was added or removed.
    stage("Push") {
        def message="lockfiles: bump to latest"
        if (!haveChanges && forceTimestamp) {
            message="lockfiles: bump timestamp"
        }
        shwrap("git -C src/config commit -am '${message}' -m 'Job URL: ${env.BUILD_URL}' -m 'Job definition: https://github.com/coreos/fedora-coreos-pipeline/blob/main/jobs/bump-lockfile.Jenkinsfile'")
        withCredentials([usernamePassword(credentialsId: botCreds,
                                          usernameVariable: 'GHUSER',
                                          passwordVariable: 'GHTOKEN')]) {
          // should gracefully handle race conditions here
          sh("git -C src/config push https://\${GHUSER}:\${GHTOKEN}@github.com/${repo} ${branch}")
        }
    }
    if (!haveChanges && forceTimestamp) {
        currentBuild.description = "[${params.STREAM}] ⚡ (pushed timestamp update)"
    } else {
        currentBuild.description = "[${params.STREAM}] ⚡ (pushed)"
    }
    currentBuild.result = 'SUCCESS'
}}}} catch (e) {
    currentBuild.result = 'FAILURE'
    throw e
} finally {
    if (currentBuild.result != 'SUCCESS') {
        slackSend(color: 'danger', message: ":fcos: :trashfire: <${env.BUILD_URL}|bump-lockfile #${env.BUILD_NUMBER} (${params.STREAM})>")
    }
}
