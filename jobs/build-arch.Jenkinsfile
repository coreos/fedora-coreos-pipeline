import org.yaml.snakeyaml.Yaml;

node {
    checkout scm
    // these are script global vars
    pipeutils = load("utils.groovy")
    pipecfg = pipeutils.load_pipecfg()
    libcloud = load("libcloud.groovy")
}

// Base URL through which to download artifacts
BUILDS_BASE_HTTP_URL = "https://builds.coreos.fedoraproject.org/prod/streams"

properties([
    pipelineTriggers([]),
    parameters([
      choice(name: 'STREAM',
             choices: pipeutils.get_streams_choices(pipecfg),
             description: 'CoreOS stream to build'),
      string(name: 'VERSION',
             description: 'Build version',
             defaultValue: '',
             trim: true),
      string(name: 'ARCH',
             description: 'The target architecture',
             choices: pipeutils.get_supported_additional_arches(),
             trim: true),
      booleanParam(name: 'FORCE',
                   defaultValue: false,
                   description: 'Whether to force a rebuild'),
      booleanParam(name: 'ALLOW_KOLA_UPGRADE_FAILURE',
                   defaultValue: false,
                   description: "Don't error out if upgrade tests fail (temporary)"),
      booleanParam(name: 'CLOUD_REPLICATION',
                   defaultValue: false,
                   description: 'Force cloud image replication for non-production'),
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
    ] + pipeutils.add_hotfix_parameters_if_supported()),
    buildDiscarder(logRotator(
        numToKeepStr: '100',
        artifactNumToKeepStr: '100'
    )),
    durabilityHint('PERFORMANCE_OPTIMIZED')
])

def build_description = "[${params.STREAM}][${params.ARCH}]"

// Reload pipecfg if a hotfix build was provided. The reason we do this here
// instead of loading the right one upfront is so that we don't modify the
// parameter definitions above and their default values.
if (params.PIPECFG_HOTFIX_REPO || params.PIPECFG_HOTFIX_REF) {
    node {
        pipecfg = pipeutils.load_pipecfg(params.PIPECFG_HOTFIX_REPO, params.PIPECFG_HOTFIX_REF)
        build_description = "[${params.STREAM}-${pipecfg.hotfix.name}][${params.ARCH}]"
    }
}

// runtime parameter always wins
def cosa_img = params.COREOS_ASSEMBLER_IMAGE
cosa_img = cosa_img ?: pipeutils.get_cosa_img(pipecfg, params.STREAM)

def stream_info = pipecfg.streams[params.STREAM]

def cosa_controller_img = stream_info.cosa_controller_img_hack ?: cosa_img

// If we are a mechanical stream then we can pin packages but we
// don't maintain complete lockfiles so we can't build in strict mode.
def strict_build_param = stream_info.type == "mechanical" ? "" : "--strict"

// Note that the heavy lifting is done on a remote node via podman
// --remote so we shouldn't need much memory.
def cosa_memory_request_mb = 2.5 * 1024 as Integer

// the build-arch pod is mostly triggering the work on a remote node, so we
// can be conservative with our request
def ncpus = 1

echo "Waiting for build-${params.STREAM}-${params.ARCH} lock"
currentBuild.description = "${build_description} Waiting"

// declare these early so we can use them in `finally` block
assert params.VERSION != ""
def newBuildID = params.VERSION
def basearch = params.ARCH

// matches between build/build-arch job
def timeout_mins = 240

// release lock: we want to block the release job until we're done.
// ideally we'd lock this from the main pipeline and have lock ownership
// transferred to us when we're triggered. in practice, it's very unlikely the
// release job would win this race.
lock(resource: "release-${params.VERSION}-${basearch}") {
// build lock: we don't want multiple concurrent builds for the same stream and
// arch (though this should work fine in theory)
lock(resource: "build-${params.STREAM}-${basearch}") {
    cosaPod(cpu: "${ncpus}",
            memory: "${cosa_memory_request_mb}Mi",
            image: cosa_controller_img,
            serviceAccount: "jenkins") {
    timeout(time: timeout_mins, unit: 'MINUTES') {
    try {

        currentBuild.description = "${build_description} Running"

        // this is defined IFF we *should* and we *can* upload to S3
        def s3_stream_dir

        if (pipecfg.s3 && pipeutils.AWSBuildUploadCredentialExists()) {
            s3_stream_dir = pipeutils.get_s3_streams_dir(pipecfg, params.STREAM)
        }

        // Now, determine if we should do any uploads to remote s3 buckets or clouds
        // Don't upload if the user told us not to or we're debugging with KOLA_RUN_SLEEP
        def uploading = false
        if (s3_stream_dir && (!params.NO_UPLOAD || params.KOLA_RUN_SLEEP)) {
            uploading = true
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

        def ref = pipeutils.get_source_config_ref_for_stream(pipecfg, params.STREAM)
        def src_config_commit
        if (params.SRC_CONFIG_COMMIT) {
            src_config_commit = params.SRC_CONFIG_COMMIT
        } else {
            src_config_commit = shwrapCapture("git ls-remote ${pipecfg.source_config.url} refs/heads/${ref} | cut -d \$'\t' -f 1")
        }

        stage('Init') {
            def yumrepos = pipecfg.source_config.yumrepos ? "--yumrepos ${pipecfg.source_config.yumrepos}" : ""
            def variant = stream_info.variant ? "--variant ${stream_info.variant}" : ""
            shwrap("""
            cosa init --force --branch ${ref} --commit=${src_config_commit} ${yumrepos} ${variant} ${pipecfg.source_config.url}
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

        // enable --autolock if not in strict mode and cosa supports it
        def autolock_arg = ""
        if (strict_build_param == "") {
            if (shwrapRc("cosa fetch --help |& grep -q autolock") == 0) {
                autolock_arg = "--autolock ${params.VERSION}"
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
                if (autolock_arg != "") {
                    // Also fetch the x86_64 lockfile for the build we're
                    // complementing so that we can use autolocking. Usually,
                    // this is the same build we just fetched above, but not
                    // necessarily.
                    pipeutils.shwrapWithAWSBuildUploadCredentials("""
                    cosa buildfetch --arch=x86_64 \
                        --build ${newBuildID} \
                        --file manifest-lock.generated.x86_64.json \
                        --url s3://${s3_stream_dir}/builds \
                        --aws-config-file \${AWS_BUILD_UPLOAD_CONFIG}
                    """)
                }
                if (parent_version != "") {
                    // also fetch the parent version; this is used by cosa to do the diff
                    pipeutils.shwrapWithAWSBuildUploadCredentials("""
                    cosa buildfetch --arch=${basearch} \
                        --build ${parent_version} \
                        --url s3://${s3_stream_dir}/builds \
                        --aws-config-file \${AWS_BUILD_UPLOAD_CONFIG}
                    """)
                }
            }
        }




        // fetch from repos for the current build
        stage('Fetch') {
            shwrap("""
            cosa fetch ${strict_build_param} ${autolock_arg}
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

        currentBuild.description = "${build_description} ⚡ ${newBuildID}"

        pipeutils.tryWithMessagingCredentials() {
            shwrap("""
            /usr/lib/coreos-assembler/fedmsg-broadcast --fedmsg-conf=\${FEDORA_MESSAGING_CONF} \
                build.state.change --build ${newBuildID} --basearch ${basearch} --stream ${params.STREAM} \
                --build-dir ${BUILDS_BASE_HTTP_URL}/${params.STREAM}/builds/${newBuildID}/${basearch} \
                --state STARTED
            """)
        }

        if (uploading) {
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

        // Build QEMU image
        stage("Build QEMU") {
            shwrap("cosa buildextend-qemu")
        }

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
                 skipUpgrade: pipecfg.hacks?.skip_upgrade_tests,
                 allowUpgradeFail: params.ALLOW_KOLA_UPGRADE_FAILURE,
                 skipSecureBoot: pipecfg.hotfix?.skip_secureboot_tests_hack)
        }

        // Build the remaining artifacts
        stage("Build Artifacts") {
            pipeutils.build_artifacts(pipecfg, params.STREAM, basearch)
        }

        // Run Kola TestISO tests for metal artifacts
        if (shwrapCapture("cosa meta --get-value images.live-iso") != "None") {
            stage("Kola:TestISO") {
                kolaTestIso(cosaDir: env.WORKSPACE, arch: basearch,
                            skipSecureBoot: pipecfg.hotfix?.skip_secureboot_tests_hack)
            }
        }

        // Upload to relevant clouds
        // XXX: we don't support cloud uploads yet for hotfixes
        if (uploading && !pipecfg.hotfix && !stream_info.skip_cloud_uploads) {
            stage('Cloud Upload') {
                libcloud.upload_to_clouds(pipecfg, basearch, newBuildID, params.STREAM)
            }
        }

        stage('Archive') {
            shwrap("cosa compress")

            if (uploading) {
                def acl = pipecfg.s3.acl ?: 'public-read'
                pipeutils.shwrapWithAWSBuildUploadCredentials("""
                cosa buildupload --skip-builds-json --arch=${basearch} s3 \
                    --aws-config-file \${AWS_BUILD_UPLOAD_CONFIG} \
                    --acl=${acl} ${s3_stream_dir}/builds
                """)
                pipeutils.bump_builds_json(
                    params.STREAM,
                    newBuildID,
                    basearch,
                    s3_stream_dir,
                    acl)
            }
        }

        // These steps interact with Fedora Infrastructure/Releng for
        // signing of artifacts and importing of OSTree commits. They
        // must be run after the archive stage because the artifacts
        // are pulled from their S3 locations. They can be run in
        // parallel.
        if (uploading) {
            pipeutils.tryWithMessagingCredentials() {
                def parallelruns = [:]
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
    def stream = params.STREAM
    if (pipecfg.hotfix) {
        stream += "-${pipecfg.hotfix.name}"
    }
    def message = "[${stream}][${basearch}] <${env.BUILD_URL}|${env.BUILD_NUMBER}>"

    if (currentBuild.result == 'SUCCESS') {
        if (!newBuildID) {
            // SUCCESS, but no new builds? Must've been a no-op
            return
        }
        message = ":sparkles: ${message}"
    } else if (currentBuild.result == 'UNSTABLE') {
        message = ":warning: ${message}"
    } else {
        message = ":fire: ${message}"
    }

    if (newBuildID) {
        message = "${message} (${newBuildID})"
    }

    echo message
    pipeutils.trySlackSend(message: message)
    pipeutils.tryWithMessagingCredentials() {
        shwrap("""
        /usr/lib/coreos-assembler/fedmsg-broadcast --fedmsg-conf=\${FEDORA_MESSAGING_CONF} \
            build.state.change --build ${newBuildID} --basearch ${basearch} --stream ${params.STREAM} \
            --build-dir ${BUILDS_BASE_HTTP_URL}/${params.STREAM}/builds/${newBuildID}/${basearch} \
            --state FINISHED --result ${currentBuild.result}
        """)
    }
}}}}} // finally, cosaPod, timeout, and locks finish here
