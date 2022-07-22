def pipeutils, streams, official
node {
    checkout scm
    pipeutils = load("utils.groovy")
    streams = load("streams.groovy")
    def pipecfg = pipeutils.load_config()
    official = pipeutils.isOfficial()
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
        string(name: 'COREOS_ASSEMBLER_IMAGE',
               description: 'Override coreos-assembler image to use',
               defaultValue: "coreos-assembler:main",
               trim: true),
    ]),
    buildDiscarder(logRotator(
        numToKeepStr: '100',
        artifactNumToKeepStr: '100'
    )),
    durabilityHint('PERFORMANCE_OPTIMIZED')
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

try { lock(resource: "bump-${params.STREAM}") { timeout(time: 120, unit: 'MINUTES') { 
    cosaPod(image: params.COREOS_ASSEMBLER_IMAGE) {
    currentBuild.description = "[${params.STREAM}] Running"

    // set up git user upfront
    shwrap("""
      git config --global user.name "CoreOS Bot"
      git config --global user.email "coreosbot@fedoraproject.org"
    """)

    def branch = params.STREAM
    def forceTimestamp = false
    def haveChanges = false
    def fcos_config_commit = shwrapCapture("git ls-remote https://github.com/${repo} ${branch} | cut -d \$'\t' -f 1")
    shwrap("cosa init --branch ${branch} --commit=${fcos_config_commit} https://github.com/${repo}")
    // we buildfetch here so we can see in the build output what packages changed
    shwrap("cosa buildfetch --arch=all --url=${BUILDS_BASE_HTTP_URL}/${branch}/builds")

    def lockfile, pkgChecksum, pkgTimestamp
    def archinfo = [x86_64: [:], aarch64: [:], s390x: [:]]
    for (arch in archinfo.keySet()) {
        // initialize some data
        archinfo[arch]['session'] = ""
        lockfile = "src/config/manifest-lock.${arch}.json"
        (pkgChecksum, pkgTimestamp) = getLockfileInfo(lockfile)
        archinfo[arch]['prevPkgChecksum'] = pkgChecksum
        archinfo[arch]['prevPkgTimestamp'] = pkgTimestamp
    }

    // We currently have a limitation where we aren't building and
    // pushing multi-arch COSA containers to quay. For multi-arch
    // we're currently building images once a day on the local
    // multi-arch builders. See https://github.com/coreos/coreos-assembler/issues/2470
    //
    // Until #2470 is resolved let's do the best thing we can do
    // which is derive the multi-arch container name from the
    // given x86_64 COSA container. We'll translate
    // quay.io/coreos-assembler/coreos-assembler:$tag -> localhost/coreos-assembler:$tag
    // This assumes that the desired tagged image has been built
    // on the multi-arch builder already, which most likely means
    // someone did it manually.
    def image = "localhost/coreos-assembler:latest"
    if (params.COREOS_ASSEMBLER_IMAGE.startsWith("quay.io/coreos-assembler/coreos-assembler:")) {
        image = params.COREOS_ASSEMBLER_IMAGE.replaceAll(
            "quay.io/coreos-assembler/coreos-assembler:",
            "localhost/coreos-assembler:"
        )
    }

    // Initialize the sessions on the remote builders
    stage("Initialize Remotes") {
        for (arch in archinfo.keySet()) {
            if (arch == "x86_64") {
                continue
            }
            pipeutils.withPodmanRemoteArchBuilder(arch: arch) {
                archinfo[arch]['session'] = \
                    shwrapCapture("cosa remote-session create --image ${image} --expiration 4h")
                withEnv(["COREOS_ASSEMBLER_REMOTE_SESSION=${archinfo[arch]['session']}"]) {
                    shwrap("""
                    cosa init --branch ${branch} --commit=${fcos_config_commit} https://github.com/${repo}
                    cosa remote-session sync ./builds/ :builds/
                    """)
                }
            }
        }
    }

    // do a first fetch where we only fetch metadata; no point in
    // importing RPMs if nothing actually changed
    stage("Fetch Metadata") {
        def parallelruns = [:]
        for (arch in archinfo.keySet()) {
            parallelruns[arch] = {
                if (arch == "x86_64") {
                    shwrap("""
                    cosa fetch --update-lockfile --dry-run
                    """)
                } else {
                    pipeutils.withExistingCosaRemoteSession(
                        arch: arch, session: archinfo[arch]['session']) {
                        shwrap("""
                        cosa fetch --update-lockfile --dry-run
                        cosa remote-session sync {:,}src/config/manifest-lock.${arch}.json
                        """)
                    }
                }
            }
        }
        parallel parallelruns
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
        // Run tests across all architectures in parallel
        parallel "aarch64": {
            def arch = "aarch64"
            def parallelruns = [:]
            pipeutils.withExistingCosaRemoteSession(
                arch: arch, session: archinfo[arch]['session']) {
            stage("${arch}:Fetch") {
                shwrap("cosa fetch --strict")
            }
            stage("${arch}:Build") {
                shwrap("cosa build --force --strict")
            }
            stage("${arch}:Kola:basic") {
                shwrap("""
                cosa kola run --rerun --basic-qemu-scenarios --no-test-exit-error
                cosa shell -- tar -c --xz tmp/kola/ > kola-run-basic.${arch}.tar.xz
                cosa shell -- cat tmp/kola/reports/report.json > report-kola-basic.${arch}.json
                """)
                archiveArtifacts "kola-run-basic.${arch}.tar.xz"
                if (!pipeutils.checkKolaSuccess("report-kola-basic.${arch}.json")) {
                    error("${arch}:Kola:basic")
                }
            }
            parallelruns["${arch}:Kola"] = {
                shwrap("""
                cosa kola run --rerun --parallel 5 --no-test-exit-error
                cosa shell -- tar -c --xz tmp/kola/ > kola-run.${arch}.tar.xz
                cosa shell -- cat tmp/kola/reports/report.json > report-kola.${arch}.json
                """)
                archiveArtifacts "kola-run.${arch}.tar.xz"
                if (!pipeutils.checkKolaSuccess("report-kola.${arch}.json")) {
                    error("${arch}:Kola")
                }
            }
            parallelruns["${arch}:Kola:upgrade"] = {
                shwrap("""
                cosa kola --rerun --upgrades --no-test-exit-error
                cosa shell -- tar -c --xz tmp/kola-upgrade/ > kola-run-upgrade.${arch}.tar.xz
                cosa shell -- cat tmp/kola-upgrade/reports/report.json > report-kola-upgrade.json
                """)
                archiveArtifacts "kola-run-upgrade.${arch}.tar.xz"
                if (!pipeutils.checkKolaSuccess("report-kola-upgrade.json")) {
                    error("${arch}:Kola:upgrade")
                }
            }
            parallel parallelruns
            stage("${arch}:Build Metal") {
                shwrap("cosa buildextend-metal")
                shwrap("cosa buildextend-metal4k")
            }
            stage("${arch}:Build Live") {
                shwrap("cosa buildextend-live --fast")
                // Test metal4k with an uncompressed image and metal with a
                // compressed one
                shwrap("cosa compress --artifact=metal")
            }
            try {
                parallel "${arch}:metal" : {
                    shwrap("cosa kola testiso -S --scenarios pxe-install,iso-install,iso-offline-install,iso-live-login,iso-as-disk --output-dir tmp/kola-testiso-metal")
                }, "${arch}:metal4k" : {
                    shwrap("cosa kola testiso -S --scenarios iso-install,iso-offline-install --qemu-native-4k --qemu-multipath --output-dir tmp/kola-testiso-metal4k")
                }
            } finally {
                shwrap("""
                cosa shell -- tar -c --xz tmp/kola-testiso-metal/ > kola-testiso-metal.${arch}.tar.xz
                cosa shell -- tar -c --xz tmp/kola-testiso-metal4k/ > kola-testiso-metal4k.${arch}.tar.xz
                """)
                archiveArtifacts allowEmptyArchive: true, artifacts: 'kola-testiso*${arch}.tar.xz'
            }
            } // end withExistingCosaRemoteSession
        }, s390x: {
            def arch = "s390x"
            def parallelruns = [:]
            pipeutils.withExistingCosaRemoteSession(
                arch: arch, session: archinfo[arch]['session']) {
            stage("${arch}:Fetch") {
                shwrap("cosa fetch --strict")
            }
            stage("${arch}:Build") {
                shwrap("cosa build --force --strict")
            }
            stage("${arch}:Kola:basic") {
                shwrap("""
                cosa kola run --rerun --basic-qemu-scenarios --no-test-exit-error
                cosa shell -- tar -c --xz tmp/kola/ > kola-run-basic.${arch}.tar.xz
                cosa shell -- cat tmp/kola/reports/report.json > report-kola-basic.${arch}.json
                """)
                archiveArtifacts "kola-run-basic.${arch}.tar.xz"
                if (!pipeutils.checkKolaSuccess("report-kola-basic.${arch}.json")) {
                    error("${arch}:Kola:basic")
                }
            }
            parallelruns["${arch}:Kola"] = {
                shwrap("""
                cosa kola run --rerun --parallel 5 --no-test-exit-error
                cosa shell -- tar -c --xz tmp/kola/ > kola-run.${arch}.tar.xz
                cosa shell -- cat tmp/kola/reports/report.json > report-kola.${arch}.json
                """)
                archiveArtifacts "kola-run.${arch}.tar.xz"
                if (!pipeutils.checkKolaSuccess("report-kola.${arch}.json")) {
                    error("${arch}:Kola")
                }
            }
            parallelruns["${arch}:Kola:upgrade"] = {
                shwrap("""
                cosa kola --rerun --upgrades --no-test-exit-error
                cosa shell -- tar -c --xz tmp/kola-upgrade/ > kola-run-upgrade.${arch}.tar.xz
                cosa shell -- cat tmp/kola-upgrade/reports/report.json > report-kola-upgrade.json
                """)
                archiveArtifacts "kola-run-upgrade.${arch}.tar.xz"
                if (!pipeutils.checkKolaSuccess("report-kola-upgrade.json")) {
                    error("${arch}:Kola:upgrade")
                }
            }
            parallel parallelruns
            stage("${arch}:Build Metal") {
                shwrap("cosa buildextend-metal")
                shwrap("cosa buildextend-metal4k")
            }
            stage("${arch}:Build Live") {
                shwrap("cosa buildextend-live --fast")
                // Test metal4k with an uncompressed image and metal with a
                // compressed one
                shwrap("cosa compress --artifact=metal")
            }
            try {
                // s390x doesn't support 4k disks or iso-installs
                stage("${arch}:metal") {
                    shwrap("cosa kola testiso -S --output-dir tmp/kola-testiso-metal")
                }
            } finally {
                shwrap("""
                cosa shell -- tar -c --xz tmp/kola-testiso-metal/ > kola-testiso-metal.${arch}.tar.xz
                """)
                archiveArtifacts allowEmptyArchive: true, artifacts: 'kola-testiso*${arch}.tar.xz'
            }
            } // end withExistingCosaRemoteSession
        }, x86_64: {
            def arch = "x86_64"
            def parallelruns = [:]
            stage("${arch}:Fetch") {
                shwrap("cosa fetch --strict")
            }
            stage("${arch}:Build") {
                shwrap("cosa build --force --strict")
            }
            stage("${arch}:Kola:basic") {
                shwrap("""
                cosa kola run --rerun --basic-qemu-scenarios --no-test-exit-error
                cosa shell -- tar -c --xz tmp/kola/ > kola-run-basic.${arch}.tar.xz
                cosa shell -- cat tmp/kola/reports/report.json > report-kola-basic.${arch}.json
                """)
                archiveArtifacts "kola-run-basic.${arch}.tar.xz"
                if (!pipeutils.checkKolaSuccess("report-kola-basic.${arch}.json")) {
                    error("${arch}:Kola:basic")
                }
            }
            parallelruns["${arch}:Kola"] = {
                shwrap("""
                cosa kola run --rerun --parallel 5 --no-test-exit-error
                cosa shell -- tar -c --xz tmp/kola/ > kola-run.${arch}.tar.xz
                cosa shell -- cat tmp/kola/reports/report.json > report-kola.${arch}.json
                """)
                archiveArtifacts "kola-run.${arch}.tar.xz"
                if (!pipeutils.checkKolaSuccess("report-kola.${arch}.json")) {
                    error("${arch}:Kola")
                }
            }
            parallelruns["${arch}:Kola:upgrade"] = {
                shwrap("""
                cosa kola --rerun --upgrades --no-test-exit-error
                cosa shell -- tar -c --xz tmp/kola-upgrade/ > kola-run-upgrade.${arch}.tar.xz
                cosa shell -- cat tmp/kola-upgrade/reports/report.json > report-kola-upgrade.json
                """)
                archiveArtifacts "kola-run-upgrade.${arch}.tar.xz"
                if (!pipeutils.checkKolaSuccess("report-kola-upgrade.json")) {
                    error("${arch}:Kola:upgrade")
                }
            }
            parallel parallelruns
            stage("${arch}:Build Metal") {
                shwrap("cosa buildextend-metal")
                shwrap("cosa buildextend-metal4k")
            }
            stage("${arch}:Build Live") {
                shwrap("cosa buildextend-live --fast")
                // Test metal4k with an uncompressed image and metal with a
                // compressed one
                shwrap("cosa compress --artifact=metal")
            }
            try {
                parallel "${arch}:metal" : {
                    shwrap("cosa kola testiso -S --scenarios pxe-install,iso-install,iso-offline-install,iso-live-login,iso-as-disk --output-dir tmp/kola-testiso-metal")
                }, "${arch}:metal4k" : {
                    shwrap("cosa kola testiso -S --scenarios iso-install,iso-offline-install --qemu-native-4k --qemu-multipath --output-dir tmp/kola-testiso-metal4k")
                }, "${arch}:uefi" : {
                    shwrap("cosa shell -- mkdir -p tmp/kola-testiso-uefi")
                    shwrap("cosa kola testiso -S --qemu-firmware=uefi --scenarios iso-live-login,iso-as-disk --output-dir tmp/kola-testiso-uefi/insecure")
                    shwrap("cosa kola testiso -S --qemu-firmware=uefi-secure --scenarios iso-live-login,iso-as-disk --output-dir tmp/kola-testiso-uefi/secure")
                }
            } finally {
                shwrap("""
                cosa shell -- tar -c --xz tmp/kola-testiso-metal/ > kola-testiso-metal.${arch}.tar.xz
                cosa shell -- tar -c --xz tmp/kola-testiso-metal4k/ > kola-testiso-metal4k.${arch}.tar.xz
                cosa shell -- tar -c --xz tmp/kola-testiso-uefi/ > kola-testiso-uefi.${arch}x86_64.tar.xz
                """)
                archiveArtifacts allowEmptyArchive: true, artifacts: 'kola-testiso*${arch}.tar.xz'
            }
        }
    }

    // Destroy the remote sessions. We don't need them anymore
    stage("Destroy Remotes") {
        for (arch in archinfo.keySet()) {
            if (arch == "x86_64") {
                continue
            }
            pipeutils.withExistingCosaRemoteSession(
                arch: arch, session: archinfo[arch]['session']) {
                shwrap("cosa remote-session destroy")
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
        shwrap("git -C src/config add manifest-lock.*.json")
        shwrap("git -C src/config commit -m '${message}' -m 'Job URL: ${env.BUILD_URL}' -m 'Job definition: https://github.com/coreos/fedora-coreos-pipeline/blob/main/jobs/bump-lockfile.Jenkinsfile'")
        withCredentials([usernamePassword(credentialsId: botCreds,
                                          usernameVariable: 'GHUSER',
                                          passwordVariable: 'GHTOKEN')]) {
          // gracefully handle race conditions
          sh("""
            rev=\$(git -C src/config rev-parse origin/${branch})
            if ! git -C src/config push https://\${GHUSER}:\${GHTOKEN}@github.com/${repo} ${branch}; then
                git -C src/config fetch origin
                if [ "\$rev" != \$(git -C src/config rev-parse origin/${branch}) ]; then
                    touch ${env.WORKSPACE}/rerun
                else
                    exit 1
                fi
            fi
          """)
        }
    }
    if (utils.pathExists("rerun")) {
        build job: 'bump-lockfile', wait: false, parameters: [
            string(name: 'STREAM', value: params.STREAM)
        ]
        currentBuild.description = "[${params.STREAM}] ⚡ (retriggered)"
    } else if (!haveChanges && forceTimestamp) {
        currentBuild.description = "[${params.STREAM}] ⚡ (pushed timestamp update)"
    } else {
        currentBuild.description = "[${params.STREAM}] ⚡ (pushed)"
    }
    currentBuild.result = 'SUCCESS'
}}}} catch (e) {
    currentBuild.result = 'FAILURE'
    throw e
} finally {
    if (official && currentBuild.result != 'SUCCESS') {
        slackSend(color: 'danger', message: ":fcos: :trashfire: <${env.BUILD_URL}|bump-lockfile #${env.BUILD_NUMBER} (${params.STREAM})>")
    }
}
