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
      choice(name: 'ARCH',
             description: 'The target architecture',
             choices: pipeutils.get_supported_additional_arches()),
      string(name: 'COREOS_ASSEMBLER_IMAGE',
             description: 'Override coreos-assembler image to use',
             defaultValue: "",
             trim: true),
      string(name: 'TIMEOUT',
             description: 'Timeout value',
             defaultValue: "4h",
             trim: true),             

    ]),
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
cosa_controller_img = 'quay.io/dustymabe/coreos-assembler-staging:cosa-tmux'
// DELETE ^^

// If we are a mechanical stream then we can pin packages but we
// don't maintain complete lockfiles so we can't build in strict mode.
def strict_build_param = stream_info.type == "mechanical" ? "" : "--strict"

// Note that the heavy lifting is done on a remote node via podman
// --remote so we shouldn't need much memory.
def cosa_memory_request_mb = 512

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
        pipeutils.withPodmanRemoteArchBuilder(arch: params.ARCH) {
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
        
        stage('Sleep') {        
            shwrap("sleep infinity")    
        }

        } // end withEnv
        } // end withPodmanRemoteArchBuilder
        currentBuild.result = 'SUCCESS'

} catch (e) {
    currentBuild.result = 'FAILURE'
    throw e
} 
}}}}
