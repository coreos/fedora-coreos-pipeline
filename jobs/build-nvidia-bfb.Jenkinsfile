import org.yaml.snakeyaml.Yaml;


node {
    checkout scm
    // these are script global vars
    pipeutils = load("utils.groovy")
    pipecfg = pipeutils.load_pipecfg()
}

properties([
    pipelineTriggers([]),
    parameters([
      string(name: 'STREAM',
             defaultValue: "4.20-9.6-nvidia-bfb",
             description: 'CoreOS stream to build',
             trim: true),
      booleanParam(name: 'FORCE',
                   defaultValue: false,
                   description: 'Whether to force a rebuild'),
      string(name: 'IMPORT_CONTAINER_IMAGE',
             description: 'The custom CoreOS input container',
             defaultValue: "quay.io/edge-infrastructure/rhcos-bfb:tp-latest",
             trim: true),
      string(name: 'COREOS_ASSEMBLER_IMAGE',
             description: 'Override coreos-assembler image to use',
             defaultValue: "",
             trim: true),
      booleanParam(name: 'NO_UPLOAD',
                   defaultValue: false,
                   description: 'Do not upload results to S3; for debugging purposes.'),
    ] + pipeutils.add_hotfix_parameters_if_supported()),
    buildDiscarder(logRotator(
        numToKeepStr: '100',
        artifactNumToKeepStr: '100'
    )),
    durabilityHint('PERFORMANCE_OPTIMIZED')
])

// NVIDIA bfb is aarch64 only for now
def basearch = 'aarch64'

def build_description = "[${params.STREAM}][${basearch}]"

// runtime parameter always wins
def cosa_img = params.COREOS_ASSEMBLER_IMAGE
cosa_img = cosa_img ?: pipeutils.get_cosa_img(pipecfg, params.STREAM)

def stream_info = pipecfg.streams[params.STREAM]

def cosa_controller_img = stream_info.cosa_controller_img ?: "quay.io/coreos-assembler/coreos-assembler:main"

// Grab any environment variables we should set
def container_env = pipeutils.get_env_vars_for_stream(pipecfg, params.STREAM)

// Note that the heavy lifting is done on a remote node via podman
// --remote so we shouldn't need much memory.
def cosa_memory_request_mb = 2.5 * 1024 as Integer

// the build-arch pod is mostly triggering the work on a remote node, so we
// can be conservative with our request
def ncpus = 1

echo "Waiting for build-${params.STREAM}-${basearch} lock"
currentBuild.description = "${build_description} Waiting"

def timeout_mins = 45
def newBuildID

// build lock: we don't want multiple concurrent builds for the same stream and
// arch (though this should work fine in theory)
lock(resource: "build-${params.STREAM}-${basearch}") {
    cosaPod(cpu: "${ncpus}",
            memory: "${cosa_memory_request_mb}Mi",
            image: cosa_controller_img,
            env: container_env,
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
        // Don't upload if the user told us not to.
        def uploading = false
        if (s3_stream_dir && !params.NO_UPLOAD) {
            uploading = true
        }

        // Wrap a bunch of commands now inside the context of a remote
        // session. All `cosa` commands, other than `cosa remote-session`
        // commands, should get intercepted and executed on the remote.
        // We set environment variables that describe our remote host
        // that `podman --remote` will transparently pick up and use.
        // We set the session to time out after 5h. This essentially
        // performs garbage collection on the remote if we fail to clean up.
        pipeutils.withPodmanRemoteArchBuilder(arch: basearch) {
        def session = pipeutils.makeCosaRemoteSession(
            env: container_env,
            expiration: "${timeout_mins}m",
            image: cosa_img,
            workdir: WORKSPACE,
        )
        withEnv(["COREOS_ASSEMBLER_REMOTE_SESSION=${session}"]) {

        // add any additional root CA cert before we do anything that fetches
        pipeutils.addOptionalRootCA()

        stage('Init') {
            def (url, ref) = pipeutils.get_source_config_for_stream(pipecfg, params.STREAM)
            def yumrepos = pipecfg.source_config.yumrepos ? "--yumrepos ${pipecfg.source_config.yumrepos}" : ""
            def variant = stream_info.variant ? "--variant ${stream_info.variant}" : ""
            shwrap("""
            cosa init --force --branch ${ref} ${yumrepos} ${variant} ${url}
            """)
        }

        // Buildfetch previous build info
        stage('BuildFetch') {
            if (s3_stream_dir) {
                pipeutils.shwrapWithAWSBuildUploadCredentials("""
                cosa buildfetch --arch=${basearch} \
                    --url s3://${s3_stream_dir}/builds \
                    --aws-config-file \${AWS_BUILD_UPLOAD_CONFIG}
                """)
            }
        }

        stage('Import Container') {
          //def version = "--version ${params.VERSION}"
          //def force = params.FORCE ? "--force" : ""
            withCredentials([file(variable: 'REGISTRY_AUTH_FILE',
                                  credentialsId: 'nvidia-bfb-container-pull-secret')]) {
                utils.syncCredentialsIfInRemoteSession(["REGISTRY_AUTH_FILE"])
                shwrap("""
                cosa shell -- env REGISTRY_AUTH_FILE=${REGISTRY_AUTH_FILE} \
                    cosa import --skip-prune docker://${params.IMPORT_CONTAINER_IMAGE}
                """)
            }
            newBuildID = shwrapCapture("cosa meta --get-value buildid")
        }

        currentBuild.description = "${build_description} ⚡ ${newBuildID}"

        stage("Build Live Artifacts") {
            shwrap("cosa osbuild live")
        }

        stage("Build BFB") {
            shwrap('''
            cosa shell <<'EOF'
                set -x
                buildid=$(cosa meta --get-value buildid)
                kernel=$(cosa meta --image-path live-kernel)
                initramfs=$(cosa meta --image-path live-initramfs)
                rootfs=$(cosa meta --image-path live-rootfs)
                ostree_repo=$(readlink -f tmp/repo)

                platform='nvidiabfb'
                imgname="rhcos-${buildid}-${platform}.aarch64.bfb"
                outfile=$(readlink -f "tmp/${imgname}")

                git -C tmp/ clone --recurse-submodules --depth=1 \
                    https://github.com/rh-ecosystem-edge/rhcos-bfb-builder.git
                cd tmp/rhcos-bfb-builder

                default_bfb=$(readlink -f tmp-default.bfb)
                capsule=$(readlink -f tmp-capsule.cap)
                infojson=$(readlink -f tmp-info.json)
                ostree --repo="${ostree_repo}" cat $buildid /usr/lib/firmware/mellanox/boot/default.bfb > $default_bfb
                ostree --repo="${ostree_repo}" cat $buildid /usr/lib/firmware/mellanox/boot/capsule/boot_update2.cap > $capsule
                ostree --repo="${ostree_repo}" cat $buildid /usr/opt/mellanox/bfb/info.json > $infojson

                ./make_bfb.sh \
                    --kernel $kernel       \
                    --initramfs $initramfs \
                    --rootfs $rootfs       \
                    --default-bfb $default_bfb \
                    --capsule $capsule         \
                    --infojson $infojson       \
                    --outfile $outfile

                cd -

                artifactjson=$(readlink -f tmp/artifactjson.json)
                checksum=$(sha256sum $outfile)
                size=$(stat -c '%s' $outfile)
                cat <<EOM > "$artifactjson"
                {
                  "images": {
                      "nvidiabfb": {
                          "path": "$imgname",
                          "sha256": "$checksum",
                          "size": $size,
                          "skip-compression": true
                      }
                  }
                }
EOM
                cosa meta --build $buildid --artifact-json $artifactjson --skip-validation

                /usr/lib/coreos-assembler/finalize-artifact \
                    $outfile "builds/${buildid}/aarch64/${imgname}"
EOF
            ''')
        }

        stage('Archive') {
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
    def message = "[${stream}][${basearch}] #${env.BUILD_NUMBER} <${env.BUILD_URL}|:jenkins:> <${env.RUN_DISPLAY_URL}|:ocean:>"

    if (currentBuild.result == 'SUCCESS') {
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
}}}} // finally, cosaPod, timeout, and locks finish here
