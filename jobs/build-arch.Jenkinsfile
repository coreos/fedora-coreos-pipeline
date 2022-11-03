import org.yaml.snakeyaml.Yaml;

def pipeutils, pipecfg, official, uploading, libupload
node {
    checkout scm
    pipeutils = load("utils.groovy")
    pipecfg = pipeutils.load_pipecfg()
    libupload = load("libupload.groovy")

    def jenkinscfg = pipeutils.load_jenkins_config()

    official = pipeutils.isOfficial()
    if (official) {
        echo "Running in official (prod) mode."
    } else {
        echo "Running in unofficial pipeline on ${env.JENKINS_URL}."
    }
}

// Base URL through which to download artifacts
BUILDS_BASE_HTTP_URL = "https://builds.coreos.fedoraproject.org/prod/streams"


properties([
    pipelineTriggers([]),
    parameters([
      choice(name: 'STREAM',
             choices: pipeutils.get_streams_choices(pipecfg),
             description: 'Fedora CoreOS stream to build'),
      string(name: 'VERSION',
             description: 'Build version',
             defaultValue: '',
             trim: true),
      string(name: 'ARCH',
             description: 'The target architecture',
             choices: pipecfg.additional_arches,
             trim: true),
      booleanParam(name: 'FORCE',
                   defaultValue: false,
                   description: 'Whether to force a rebuild'),
      booleanParam(name: 'ALLOW_KOLA_UPGRADE_FAILURE',
                   defaultValue: false,
                   description: "Don't error out if upgrade tests fail (temporary)"),
      booleanParam(name: 'AWS_REPLICATION',
                   defaultValue: false,
                   description: 'Force AWS AMI replication for non-production'),
      string(name: 'COREOS_ASSEMBLER_IMAGE',
             description: 'Override coreos-assembler image to use',
             defaultValue: "",
             trim: true),
      booleanParam(name: 'KOLA_RUN_SLEEP',
                   defaultValue: false,
                   description: 'Wait forever at kola tests stage. Implies NO_UPLOAD'),
      booleanParam(name: 'NO_UPLOAD',
                   defaultValue: false,
                   description: 'Do not upload results to S3; for debugging purposes.'),
      string(name: 'SRC_CONFIG_COMMIT',
             description: 'The exact config repo git commit to build against',
             defaultValue: '',
             trim: true),
    ]),
    buildDiscarder(logRotator(
        numToKeepStr: '100',
        artifactNumToKeepStr: '100'
    )),
    durabilityHint('PERFORMANCE_OPTIMIZED')
])

// runtime parameter always wins
def cosa_img = params.COREOS_ASSEMBLER_IMAGE
cosa_img = cosa_img ?: pipeutils.get_cosa_img(pipecfg, params.STREAM)

def stream_info = pipecfg.streams[params.STREAM]

def cosa_controller_img = stream_info.cosa_controller_img_hack ?: cosa_img

// If we are a mechanical stream then we can pin packages but we
// don't maintin complete lockfiles so we can't build in strict mode.
def strict_build_param = stream_info.type == "mechanical" ? "" : "--strict"

// Note that the heavy lifting is done on a remote node via podman
// --remote so we shouldn't need much memory.
def cosa_memory_request_mb = 2.5 * 1024 as Integer

// the build-arch pod is mostly triggering the work on a remote node, so we
// can be conservative with our request
def ncpus = 1

echo "Waiting for build-${params.STREAM}-${params.ARCH} lock"
currentBuild.description = "[${params.STREAM}][${params.ARCH}] Waiting"

// declare these early so we can use them in `finally` block
assert params.VERSION != ""
def newBuildID = params.VERSION
def basearch = params.ARCH

// release lock: we want to block the release job until we're done.
// ideally we'd lock this from the main pipeline and have lock ownership
// transferred to us when we're triggered. in practice, it's very unlikely the
// release job would win this race.
lock(resource: "release-${params.VERSION}-${basearch}") {
// build lock: we don't want multiple concurrent builds for the same stream and
// arch (though this should work fine in theory)
lock(resource: "build-${params.STREAM}-${basearch}") {
    timeout(time: 240, unit: 'MINUTES') {
    cosaPod(cpu: "${ncpus}",
            memory: "${cosa_memory_request_mb}Mi",
            image: cosa_controller_img) {
    try {

        currentBuild.description = "[${params.STREAM}][${basearch}] Running"


        // this is defined IFF we *should* and we *can* upload to S3
        def s3_stream_dir

        if (pipecfg.s3 && pipeutils.AWSBuildUploadCredentialExists()) {
            s3_stream_dir = pipeutils.get_s3_streams_dir(pipecfg, params.STREAM)
        }

        // Now, determine if we should do any uploads to remote s3 buckets or clouds
        // Don't upload if the user told us not to or we're debugging with KOLA_RUN_SLEEP
        if (s3_stream_dir && (!params.NO_UPLOAD || params.KOLA_RUN_SLEEP)) {
            uploading = true
        } else {
            uploading = false
        }


        // Wrap a bunch of commands now inside the context of a remote
        // session. All `cosa` commands, other than `cosa remote-session`
        // commands, should get intercepted and executed on the remote.
        // We set environment variables that describe our remote host
        // that `podman --remote` will transparently pick up and use.
        // We set the session to time out after 4h. This essentially
        // performs garbage collection on the remote if we fail to clean up.
        pipeutils.withPodmanRemoteArchBuilder(arch: basearch) {
        def session = shwrapCapture("""
        cosa remote-session create --image ${cosa_img} --expiration 4h --workdir ${env.WORKSPACE}
        """)
        withEnv(["COREOS_ASSEMBLER_REMOTE_SESSION=${session}"]) {

        // add any additional root CA cert before we do anything that fetches
        pipeutils.addOptionalRootCA()

        def local_builddir = "/srv/devel/streams/${params.STREAM}"
        def ref = pipeutils.get_source_config_ref_for_stream(pipecfg, params.STREAM)
        def src_config_commit
        if (params.SRC_CONFIG_COMMIT) {
            src_config_commit = params.SRC_CONFIG_COMMIT
        } else {
            src_config_commit = shwrapCapture("git ls-remote ${pipecfg.source_config.url} ${ref} | cut -d \$'\t' -f 1")
        }

        stage('Init') {
            def yumrepos = pipecfg.source_config.yumrepos ? "--yumrepos ${pipecfg.source_config.yumrepos}" : ""

            shwrap("""
            cosa init --force --branch ${ref} --commit=${src_config_commit} ${yumrepos} ${pipecfg.source_config.url}
            """)

        }

        // Determine parent version/commit information
        def parent_version = ""
        def parent_commit = ""
        if (s3_stream_dir) {
            pipeutils.aws_s3_cp_allow_noent("s3://${s3_stream_dir}/releases.json", "releases.json")
            if (utils.pathExists("releases.json")) {
                def releases = readJSON file: "releases.json"
                // check if there's a previous release we should use as parent
                for (release in releases["releases"].reverse()) {
                    def commit_obj = release["commits"].find{ commit -> commit["architecture"] == basearch }
                    if (commit_obj != null) {
                        parent_commit = commit_obj["checksum"]
                        parent_version = release["version"]
                        break
                    }
                }
            }
        }

        // buildfetch previous build info
        stage('BuildFetch') {
            if (s3_stream_dir) {
                pipeutils.shwrapWithAWSBuildUploadCredentials("""
                cosa buildfetch --arch=${basearch} \
                    --url s3://${s3_stream_dir}/builds \
                    --aws-config-file \${AWS_BUILD_UPLOAD_CONFIG}
                """)
                if (parent_version != "") {
                    // also fetch the parent version; this is used by cosa to do the diff
                    pipeutils.shwrapWithAWSBuildUploadCredentials("""
                    cosa buildfetch --arch=${basearch} \
                        --build ${parent_version} \
                        --url s3://${s3_stream_dir}/builds \
                        --aws-config-file \${AWS_BUILD_UPLOAD_CONFIG}
                    """)
                }
            } else if (utils.pathExists(local_builddir)) {
                // if using local builddir then sync it from local and then
                // push to the remote
                shwrap("""
                COREOS_ASSEMBLER_REMOTE_SESSION= \
                    cosa buildfetch --url=${local_builddir} --arch=${basearch}
                cosa remote-session sync ./ :/srv/
                """)
            }
        }




        // fetch from repos for the current build
        stage('Fetch') {
            shwrap("""
            cosa fetch ${strict_build_param}
            """)
        }

        stage('Build OSTree') {
            def parent_arg = ""
            if (parent_version != "") {
                parent_arg = "--parent-build ${parent_version}"
            }
            def version = "--version ${params.VERSION}"
            def force = params.FORCE ? "--force" : ""
            shwrap("""
            cosa build ostree ${strict_build_param} --skip-prune ${force} ${version} ${parent_arg}
            """)

            // Insert the parent info into meta.json so we can display it in
            // the release browser and for sanity checking
            if (parent_commit && parent_version) {
                shwrap("""
                cosa meta \
                    --set fedora-coreos.parent-commit=${parent_commit} \
                    --set fedora-coreos.parent-version=${parent_version}
                """)
            }
        }

        currentBuild.description = "[${params.STREAM}][${basearch}] ⚡ ${newBuildID}"


        if (official) {
            pipeutils.tryWithMessagingCredentials() {
                shwrap("""
                /usr/lib/coreos-assembler/fedmsg-broadcast --fedmsg-conf=\${FEDORA_MESSAGING_CONF} \
                    build.state.change --build ${newBuildID} --basearch ${basearch} --stream ${params.STREAM} \
                    --build-dir ${BUILDS_BASE_HTTP_URL}/${params.STREAM}/builds/${newBuildID}/${basearch} \
                    --state STARTED
                """)
            }
        }

        if (official && uploading) {
            pipeutils.tryWithMessagingCredentials() {
                stage('Sign OSTree') {
                    pipeutils.shwrapWithAWSBuildUploadCredentials("""
                    cosa sign --build=${newBuildID} --arch=${basearch} \
                        robosignatory --s3 ${s3_stream_dir}/builds \
                        --aws-config-file \${AWS_BUILD_UPLOAD_CONFIG} \
                        --extra-fedmsg-keys stream=${params.STREAM} \
                        --ostree --gpgkeypath /etc/pki/rpm-gpg \
                        --fedmsg-conf=\${FEDORA_MESSAGING_CONF}
                    """)
                }
            }
        }

        // A few independent tasks that can be run in parallel
        def parallelruns = [:]

        // Generate KeyLime hashes for attestation on builds
        // This is a POC setup and will be modified over time
        // See: https://github.com/keylime/enhancements/blob/master/16_remote_allowlist_retrieval.md
        parallelruns['KeyLime Hash Generation'] = {
            shwrap("""
            cosa generate-hashlist --arch=${basearch} --release=${newBuildID} \
                --output=builds/${newBuildID}/${basearch}/exp-hash.json
            source="builds/${newBuildID}/${basearch}/exp-hash.json"
            target="builds/${newBuildID}/${basearch}/exp-hash.json-CHECKSUM"
            cosa shell -- bash -c "sha256sum \$source > \$target"
            """)
        }

        // Build QEMU image
        parallelruns['Build QEMU'] = {
            shwrap("cosa buildextend-qemu")
        }

        // process this batch
        parallel parallelruns

        // This is a temporary hack to help debug https://github.com/coreos/fedora-coreos-tracker/issues/1108.
        if (params.KOLA_RUN_SLEEP) {
            echo "Hit KOLA_RUN_SLEEP; going to sleep..."
            shwrap("sleep infinity")
            throw new Exception("unreachable")
        }

        // Run Kola Tests
        stage("Kola") {
            def n = 4 // VMs are 2G each and arch builders have approx 32G
            kola(cosaDir: env.WORKSPACE, parallel: n, arch: basearch,
                 allowUpgradeFail: params.ALLOW_KOLA_UPGRADE_FAILURE,
                 skipSecureBoot: pipecfg.hotfix?.skip_secureboot_tests_hack)
        }

        // Build the remaining artifacts
        stage("Build Artifacts") {
            pipeutils.build_artifacts(pipecfg, params.STREAM, basearch)

        }

        // Hack for serial console on aarch64 aws images
        // see https://github.com/coreos/fedora-coreos-tracker/issues/920#issuecomment-914334988
        // Right now we only patch if platforms.yaml hasn't made it to this stream yet.
        // Fold this back into the above parallel runs (i.e. add to config.yaml
        // artifacts list for aarch64 and delete below code and knob) once platforms.yaml
        // exists everywhere. https://github.com/coreos/fedora-coreos-config/pull/1181
        if (basearch == "aarch64" && pipecfg.aws_aarch64_serial_console_hack) {
            stage('💽:aws') {
                shwrap("""
                if ! cosa shell -- test -e src/config/platforms.yaml; then
                    echo 'ZGlmZiAtLWdpdCBhL3NyYy9nZi1zZXQtcGxhdGZvcm0gYi9zcmMvZ2Ytc2V0LXBsYXRmb3JtCmluZGV4IDNiMWM1YWUzMS4uZGY1ZTBmOWQ3IDEwMDc1NQotLS0gYS9zcmMvZ2Ytc2V0LXBsYXRmb3JtCisrKyBiL3NyYy9nZi1zZXQtcGxhdGZvcm0KQEAgLTU5LDcgKzU5LDEzIEBAIGJsc2NmZ19wYXRoPSQoY29yZW9zX2dmIGdsb2ItZXhwYW5kIC9ib290L2xvYWRlci9lbnRyaWVzL29zdHJlZS0qLmNvbmYpCiBjb3Jlb3NfZ2YgZG93bmxvYWQgIiR7YmxzY2ZnX3BhdGh9IiAiJHt0bXBkfSIvYmxzLmNvbmYKICMgUmVtb3ZlIGFueSBwbGF0Zm9ybWlkIGN1cnJlbnRseSB0aGVyZQogc2VkIC1pIC1lICdzLCBpZ25pdGlvbi5wbGF0Zm9ybS5pZD1bYS16QS1aMC05XSosLGcnICIke3RtcGR9Ii9ibHMuY29uZgotc2VkIC1pIC1lICcvXm9wdGlvbnMgLyBzLCQsIGlnbml0aW9uLnBsYXRmb3JtLmlkPSciJHtwbGF0Zm9ybWlkfSInLCcgIiR7dG1wZH0iL2Jscy5jb25mCitpZiBbICIkKGNvcmVvc19nZiBleGlzdHMgL2Jvb3QvY29yZW9zL3BsYXRmb3Jtcy5qc29uKSIgIT0gInRydWUiIC1hICIke3BsYXRmb3JtaWR9IiA9PSAnYXdzJyBdOyB0aGVuCisgICAgIyBPdXIgcGxhdGZvcm0gaXMgQVdTIGFuZCB3ZSBzdGlsbCBuZWVkIHRoZSBjb25zb2xlPXR0eVMwIGhhY2sgZm9yIHRoZSBsZWdhY3kKKyAgICAjIChubyBwbGF0Zm9ybXMueWFtbCkgcGF0aC4KKyAgICBzZWQgLWkgLWUgJ3N8Xlwob3B0aW9ucyAuKlwpfFwxIGlnbml0aW9uLnBsYXRmb3JtLmlkPSciJHtwbGF0Zm9ybWlkfSInIGNvbnNvbGU9dHR5UzAsMTE1MjAwbjh8JyAiJHt0bXBkfSIvYmxzLmNvbmYKK2Vsc2UKKyAgICBzZWQgLWkgLWUgJy9eb3B0aW9ucyAvIHMsJCwgaWduaXRpb24ucGxhdGZvcm0uaWQ9JyIke3BsYXRmb3JtaWR9IicsJyAiJHt0bXBkfSIvYmxzLmNvbmYKK2ZpCiBpZiBbIC1uICIkcmVtb3ZlX2thcmdzIiBdOyB0aGVuCiAgICAgIyBSZW1vdmUgZXhpc3RpbmcgcWVtdS1zcGVjaWZpYyBrYXJncwogICAgIHNlZCAtaSAtZSAnL15vcHRpb25zIC8gc0AgJyIke3JlbW92ZV9rYXJnc30iJ0BAJyAiJHt0bXBkfSIvYmxzLmNvbmYKCg==' | base64 --decode | cosa shell -- sudo patch /usr/lib/coreos-assembler/gf-set-platform
                fi
                """)
                shwrap("cosa buildextend-aws")
            }
        }

        // Run Kola TestISO tests for metal artifacts
        if (shwrapCapture("cosa meta --get-value images.live-iso") != "None") {
            stage("Kola:TestISO") {
                kolaTestIso(cosaDir: env.WORKSPACE, arch: basearch,
                            skipSecureBoot: pipecfg.hotfix?.skip_secureboot_tests_hack)
            }
        }

        // Upload to relevant clouds
        if (uploading) {
            stage('Cloud Upload') {
                libupload.upload_to_clouds(pipecfg, basearch, newBuildID, params.STREAM)
            }
        }

        stage('Archive') {
            shwrap("""
            cosa compress --compressor xz
            """)

            if (uploading) {
                // just upload as public-read for now, but see discussions in
                // https://github.com/coreos/fedora-coreos-tracker/issues/189
                pipeutils.shwrapWithAWSBuildUploadCredentials("""
                cosa buildupload --skip-builds-json s3 \
                    --aws-config-file \${AWS_BUILD_UPLOAD_CONFIG} \
                    --acl=public-read ${s3_stream_dir}/builds
                """)
                pipeutils.bump_builds_json(
                    params.STREAM,
                    newBuildID,
                    basearch,
                    s3_stream_dir)
            } else {
                // Without an S3 server, just archive into the PVC
                // itself. Otherwise there'd be no other way to retrieve the
                // artifacts. But note we only keep one build at a time.
                shwrap("""
                rm -rf ${local_builddir}
                mkdir -p ${local_builddir}
                cosa remote-session sync :builds/ ${local_builddir}
                """)
            }
        }

        // These steps interact with Fedora Infrastructure/Releng for
        // signing of artifacts and importing of OSTree commits. They
        // must be run after the archive stage because the artifacts
        // are pulled from their S3 locations. They can be run in
        // parallel.
        if (official && uploading) {
            pipeutils.tryWithMessagingCredentials() {
                parallelruns = [:]
                parallelruns['Sign Images'] = {
                    pipeutils.shwrapWithAWSBuildUploadCredentials("""
                    cosa sign --build=${newBuildID} --arch=${basearch} \
                        robosignatory --s3 ${s3_stream_dir}/builds \
                        --aws-config-file \${AWS_BUILD_UPLOAD_CONFIG} \
                        --extra-fedmsg-keys stream=${params.STREAM} \
                        --images --gpgkeypath /etc/pki/rpm-gpg \
                        --fedmsg-conf \${FEDORA_MESSAGING_CONF}
                    """)
                }
                parallelruns['OSTree Import: Compose Repo'] = {
                    shwrap("""
                    cosa shell -- \
                    /usr/lib/coreos-assembler/fedmsg-send-ostree-import-request \
                        --build=${newBuildID} --arch=${basearch} \
                        --s3=${s3_stream_dir} --repo=compose \
                        --fedmsg-conf \${FEDORA_MESSAGING_CONF}
                    """)
                }
                // process this batch
                parallel parallelruns
            }
        }

        // Now that the metadata is uploaded go ahead and kick off some followup tests.
        if (uploading) {
            stage('Cloud Tests') {
                pipeutils.run_cloud_tests(pipecfg, params.STREAM, newBuildID,
                                          s3_stream_dir, basearch, src_config_commit)
            }
        }

        stage('Destroy Remote') {
            shwrap("cosa remote-session destroy")
        }

        } // end withEnv
        } // end withPodmanRemoteArchBuilder


        currentBuild.result = 'SUCCESS'

} catch (e) {
    currentBuild.result = 'FAILURE'
    throw e
} finally {
    def color
    def message = "[${params.STREAM}][${basearch}] <${env.BUILD_URL}|${env.BUILD_NUMBER}>"

    if (currentBuild.result == 'SUCCESS') {
        if (!newBuildID) {
            // SUCCESS, but no new builds? Must've been a no-op
            return
        }
        message = ":fcos: :sparkles: ${message} - SUCCESS"
        color = 'good';
    } else if (currentBuild.result == 'UNSTABLE') {
        message = ":fcos: :warning: ${message} - WARNING"
        color = 'warning';
    } else {
        message = ":fcos: :trashfire: ${message} - FAILURE"
        color = 'danger';
    }

    if (newBuildID) {
        message = "${message} (${newBuildID})"
    }

    echo message
    pipeutils.trySlackSend(color: color, message: message)
    if (official) {
        pipeutils.tryWithMessagingCredentials() {
            shwrap("""
            /usr/lib/coreos-assembler/fedmsg-broadcast --fedmsg-conf=\${FEDORA_MESSAGING_CONF} \
                build.state.change --build ${newBuildID} --basearch ${basearch} --stream ${params.STREAM} \
                --build-dir ${BUILDS_BASE_HTTP_URL}/${params.STREAM}/builds/${newBuildID}/${basearch} \
                --state FINISHED --result ${currentBuild.result}
            """)
        }
    }
}}}}} // finally, cosaPod, timeout, and locks finish here
