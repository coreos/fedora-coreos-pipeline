node {
    checkout scm
    // these are script global vars
    pipeutils = load("utils.groovy")
    pipecfg = pipeutils.load_pipecfg()
}

repo = "coreos/fedora-coreos-config"
botCreds = "github-coreosbot-token-username-password"

properties([
    // we're only triggered by bump-lockfiles
    pipelineTriggers([]),
    parameters([
        choice(name: 'STREAM',
               choices: pipeutils.streams_of_type(pipecfg, 'development'),
               description: 'CoreOS development stream to bump'),
        string(name: 'SKIP_TESTS_ARCHES',
               description: 'Space-separated list of architectures to skip tests on',
               defaultValue: "",
               trim: true),
        string(name: 'COREOS_ASSEMBLER_IMAGE',
               description: 'Override coreos-assembler image to use',
               defaultValue: "",
               trim: true),
        booleanParam(name: 'ALLOW_KOLA_UPGRADE_FAILURE',
                     defaultValue: false,
                     description: "Don't error out if upgrade tests fail (temporary)"),
        booleanParam(name: 'SKIP_BOOTC_IMAGE_BUMP',
                     defaultValue: false,
                     description: "Don't attempt to bump the bootc image."),
    ]),
    buildDiscarder(logRotator(
        numToKeepStr: '100',
        artifactNumToKeepStr: '30'
    )),
    durabilityHint('PERFORMANCE_OPTIMIZED')
])

// runtime parameter always wins
def cosa_img = params.COREOS_ASSEMBLER_IMAGE
cosa_img = cosa_img ?: pipeutils.get_cosa_img(pipecfg, params.STREAM)

currentBuild.description = "[${params.STREAM}] Waiting"

def s3_stream_dir = pipeutils.get_s3_streams_dir(pipecfg, params.STREAM)

def stream_info = pipecfg.streams[params.STREAM]

// Grab any environment variables we should set
def container_env = pipeutils.get_env_vars_for_stream(pipecfg, params.STREAM)

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

// Keep in sync with build.Jenkinsfile
def cosa_memory_request_mb = 10.5 * 1024 as Integer
def ncpus = ((cosa_memory_request_mb - 512) / 1536) as Integer
def timeout_mins = 300

lock(resource: "bump-${params.STREAM}") {
echo "Waiting for bump-lockfile lock"
lock(resource: "bump-lockfile") {
    cosaPod(image: cosa_img, env: container_env,
            cpu: "${ncpus}", memory: "${cosa_memory_request_mb}Mi",
            serviceAccount: "jenkins") {
    timeout(time: timeout_mins, unit: 'MINUTES') {
    try {

        currentBuild.description = "[${params.STREAM}] Running"

        // add any additional root CA cert before we do anything that fetches
        pipeutils.addOptionalRootCA()

        // set up git user upfront
        shwrap("""
          git config --global user.name "CoreOS Bot"
          git config --global user.email "coreosbot@fedoraproject.org"
        """)

        def branch = params.STREAM
        def forceTimestamp = false
        def haveChanges = false
        def src_config_commit = shwrapCapture("git ls-remote https://github.com/${repo} refs/heads/${branch} | cut -d \$'\t' -f 1")
        def variant = stream_info.variant ? "--variant ${stream_info.variant}" : ""
        shwrap("cosa init --branch ${branch} ${variant} --commit=${src_config_commit} https://github.com/${repo}")

        def lockfile, pkgChecksum, pkgTimestamp
        def skip_tests_arches = params.SKIP_TESTS_ARCHES.split()
        def arches = pipeutils.get_additional_arches(pipecfg, params.STREAM).plus("x86_64")
        def archinfo = arches.collectEntries{[it, [:]]}
        for (architecture in archinfo.keySet()) {
            def arch = architecture
            // initialize some data
            archinfo[arch]['session'] = ""
            lockfile = "src/config/manifest-lock.${arch}.json"
            (pkgChecksum, pkgTimestamp) = getLockfileInfo(lockfile)
            archinfo[arch]['prevPkgChecksum'] = pkgChecksum
            archinfo[arch]['prevPkgTimestamp'] = pkgTimestamp
        }

        if (!params.SKIP_BOOTC_IMAGE_BUMP) {
            stage("Bump bootc") {
                shwrap('''
                    builder_img_repo=$(
                        grep BUILDER_IMG= src/config/build-args.conf |
                        cut -d = -f 2                                |
                        cut -d @ -f 1
                    )
                    latest_sha256sum_id=$(
                        skopeo inspect -n --raw docker://${builder_img_repo}:latest |
                        sha256sum | cut -d ' ' -f 1
                    )
                    new_builder_img="${builder_img_repo}@sha256:${latest_sha256sum_id}"
                    sed -i "s|^BUILDER_IMG=.*|BUILDER_IMG=${new_builder_img}|" src/config/build-args.conf
                ''')
                if (shwrapRc("git -C src/config diff --exit-code src/config/build-args.conf") != 0) {
                    haveChanges = true
                }
            }
        }

        // Initialize the sessions on the remote builders
        stage("Initialize Remotes") {
            parallel archinfo.keySet().collectEntries{arch -> [arch, {
                if (arch != "x86_64") {
                    pipeutils.withPodmanRemoteArchBuilder(arch: arch) {
                        archinfo[arch]['session'] = pipeutils.makeCosaRemoteSession(
                            env: container_env,
                            expiration: "${timeout_mins}m",
                            image: cosa_img,
                            workdir: WORKSPACE,
                        )
                        withEnv(["COREOS_ASSEMBLER_REMOTE_SESSION=${archinfo[arch]['session']}"]) {
                            shwrap("""
                            cosa init --branch ${branch} ${variant} --commit=${src_config_commit} https://github.com/${repo}
                            # Also sync over the upate to the bootc builder image
                            cosa remote-session sync {,:}src/config/build-args.conf
                            """)
                        }
                    }
                }
            }]}
        }

        // do a first fetch where we only fetch metadata; no point in
        // importing RPMs if nothing actually changed. We also do a
        // buildfetch here so we can see in the build output (that happens
        // later) what packages changed.
        stage("Fetch Metadata") {
            def parallelruns = [:]
            for (architecture in archinfo.keySet()) {
                def arch = architecture
                parallelruns[arch] = {
                    if (arch == "x86_64") {
                        pipeutils.shwrapWithAWSBuildUploadCredentials("""
                        cosa buildfetch --arch=${arch} --find-build-for-arch \
                            --aws-config-file \${AWS_BUILD_UPLOAD_CONFIG} \
                            --url=s3://${s3_stream_dir}/builds
                        cosa shell -- env COSA_BUILD_WITH_BUILDAH=0 cosa fetch --update-lockfile --dry-run --konflux
                        """)
                    } else {
                        pipeutils.withExistingCosaRemoteSession(
                            arch: arch, session: archinfo[arch]['session']) {
                            pipeutils.shwrapWithAWSBuildUploadCredentials("""
                            cosa buildfetch --arch=${arch} --find-build-for-arch \
                                --aws-config-file \${AWS_BUILD_UPLOAD_CONFIG} \
                                --url=s3://${s3_stream_dir}/builds
                            cosa shell -- env COSA_BUILD_WITH_BUILDAH=0 cosa fetch --update-lockfile --dry-run --konflux
                            cosa remote-session sync {:,}src/config/manifest-lock.${arch}.json
                            cosa remote-session sync {:,}src/config/${arch}.rpms.lock.yaml
                            """)
                        }
                    }
                }
            }
            parallel parallelruns
        }

        for (architecture in archinfo.keySet()) {
            def arch = architecture
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

        // Merge the hermeto lockfiles and delete the intermediaries files
        def archExpansion = "{${archinfo.keySet().join(',')}}"
        shwrap("""
          pushd src/config
          /usr/lib/coreos-assembler/konflux-rpm-lockfile merge --input ${archExpansion}.rpms.lock.yaml --override konflux-lockfile-override.yaml
          rm ${archExpansion}.rpms.lock.yaml
          popd
        """)
        
        // sanity-check only base and konflux lockfiles were changed
        shwrap("""
          # do this separately so set -e kicks in if it fails
          files=\$(git -C src/config ls-files --modified --deleted)
          for f in \${files}; do
            if ! [[ \${f} =~ ^(manifest-lock\\.[0-9a-z_]+\\.json|rpms\\.lock\\.yaml|build-args\\.conf) ]]; then
              echo "Unexpected modified file \${f}"
              exit 1
            fi
          done
        """)

        // The bulk of the work (build, test, etc) is done in the following.
        // We only need to do that work if we have changes.
        if (haveChanges) {
            // Run tests across all architectures in parallel
            def outerparallelruns = [:]
            for (architecture in archinfo.keySet()) {
                def arch = architecture
                if (arch in skip_tests_arches) {
                    // The user has explicitly told us that it is OK to
                    // skip tests on this architecture. Presumably because
                    // they already passed in a previous run.
                    continue
                }
                outerparallelruns[arch] = {
                    def buildAndTest = {
                        def parallelruns = [:]
                        stage("${arch}:Fetch") {
                            shwrap("cosa fetch --strict")
                        }
                        stage("${arch}:Build OSTree") {
                            shwrap("cosa build ostree --force --strict")
                        }
                        stage("${arch}:OSBuild") {
                            shwrap("cosa osbuild qemu metal metal4k live")
                        }
                        def n = ncpus - 1 // remove 1 for upgrade test
                        kola(cosaDir: env.WORKSPACE, parallel: n, arch: arch,
                             marker: arch, allowUpgradeFail: params.ALLOW_KOLA_UPGRADE_FAILURE,
                             skipKolaTags: stream_info.skip_kola_tags)
                        stage("${arch}:Compress Metal") {
                            // Test metal4k with an uncompressed image and
                            // metal with a compressed one. Limit to 4G to be
                            // good neighbours and reduce chances of getting
                            // OOMkilled.
                            shwrap("cosa shell -- env XZ_DEFAULTS=--memlimit=4G cosa compress --artifact=metal")
                        }
                        stage("${arch}:kola:testiso") {
                            kolaTestIso(cosaDir: env.WORKSPACE, arch: arch, marker: arch)
                        }
                    }
                    if (arch == "x86_64") {
                        buildAndTest()
                    } else {
                        pipeutils.withExistingCosaRemoteSession(
                            arch: arch, session: archinfo[arch]['session']) {
                            buildAndTest()
                      }
                    }
                } // end outerparallelruns
            } // end for loop
            parallel outerparallelruns
        }

        // Destroy the remote sessions. We don't need them anymore
        stage("Destroy Remotes") {
            parallel archinfo.keySet().collectEntries{arch -> [arch, {
                if (arch != "x86_64") {
                    pipeutils.withExistingCosaRemoteSession(
                        arch: arch, session: archinfo[arch]['session']) {
                        shwrap("cosa remote-session destroy")
                    }
                }
            }]}
        }

        // OK, we're ready to push: just push to the branch. In the future, we might be
        // fancier here; e.g. if tests fail, just open a PR, or if tests passed but a
        // package was added or removed.
        stage("Push") {
            def message="lockfiles: bump to latest"
            if (!haveChanges && forceTimestamp) {
                message="lockfiles: bump timestamp"
            }

            shwrap("git -C src/config add rpms.lock.yaml manifest-lock.*.json build-args.conf")
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
            stage("Trigger Build") {
                echo "Triggering build for stream: ${params.STREAM}"
                build job: 'build', wait: false, propagate: false, parameters: [
                  string(name: 'STREAM', value: params.STREAM),
                  booleanParam(name: 'EARLY_ARCH_JOBS', value: false),
                  booleanParam(name: 'SKIP_UNTESTED_ARTIFACTS',
                               value: pipeutils.should_we_skip_untested_artifacts(pipecfg))
                ]
            }
        }
        currentBuild.result = 'SUCCESS'

    } catch (e) {
        currentBuild.result = 'FAILURE'
        throw e
    } finally {
        if (currentBuild.result != 'SUCCESS') {
            pipeutils.trySlackSend(message: "bump-lockfile #${env.BUILD_NUMBER} <${env.BUILD_URL}|:jenkins:> <${env.RUN_DISPLAY_URL}|:ocean:> [${params.STREAM}]")
        }
    }
}}}} // cosaPod, timeout, and locks finish here
