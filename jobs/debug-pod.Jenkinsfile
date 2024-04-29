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
             description: 'Timeout value (in hours)',
             defaultValue: "8",
             trim: true),  
    ]),
    buildDiscarder(logRotator(
        numToKeepStr: '15',
        artifactNumToKeepStr: '15'
    )),
    durabilityHint('PERFORMANCE_OPTIMIZED')
])

// Retrieve the username logged in from Jenkins 
def userName = currentBuild.getBuildCauses('hudson.model.Cause$UserIdCause')[0]?.userName
def build_description = "[${userName}][${params.STREAM}][${params.ARCH}]"

// runtime parameter always wins
def cosa_img = params.COREOS_ASSEMBLER_IMAGE
cosa_img = cosa_img ?: pipeutils.get_cosa_img(pipecfg, params.STREAM)

def stream_info = pipecfg.streams[params.STREAM]

// Grab any environment variables we should set
def container_env = pipeutils.get_env_vars_for_stream(pipecfg, params.STREAM)

// We'll just always use main for the controller image here.
def cosa_controller_img = "quay.io/coreos-assembler/coreos-assembler:main"

// Note that the heavy lifting is done on a remote node via podman
// --remote so we shouldn't need much memory or CPU.
def cosa_memory_request_mb = 512
def ncpus = 1

// Give ourselves a unique pod name that the user can identify
def podName = "debug-pod-$userName-${params.ARCH}-"
podName += UUID.randomUUID().toString().substring(0, 8)

cosaPod(cpu: "${ncpus}",
        memory: "${cosa_memory_request_mb}Mi",
        image: cosa_controller_img,
        env: container_env,
        serviceAccount: "jenkins",
        name : podName) {
    timeout(time: params.TIMEOUT as Integer, unit: 'HOURS') {
    try {

        currentBuild.description = "${build_description} Preparing"

        // Wrap a bunch of commands now inside the context of a remote
        // session. All `cosa` commands, other than `cosa remote-session`
        // commands, should get intercepted and executed on the remote.
        // We set environment variables that describe our remote host
        // that `podman --remote` will transparently pick up and use.
        // We set the session to time out after 4h. This essentially
        // performs garbage collection on the remote if we fail to clean up.
        pipeutils.withPodmanRemoteArchBuilder(arch: params.ARCH) {
        def session = pipeutils.makeCosaRemoteSession(
            env: container_env,
            expiration: "${params.TIMEOUT}h",
            image: cosa_img,
            workdir: WORKSPACE,
        )
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

        stage('Create Debug Session') {
            shwrap("""
            # Set SHELL=/bin/sh because inside OpenShift the user has /sbin/nologin
            # as the shell in /etc/passwd.
            # create a new tmux session with two panes. 
            export SHELL=/bin/sh
            tmux new-session -d "bash"';' split-window "bash"';' detach || :
            # sleep to give the bash shells a moment to start before
            # we start sending keystrokes. If we don't sleep we'll get
            # the keystrokes twice on the screen, which is ugly.
            sleep 2
            # In the top pane ssh into the builder (allows running podman directly)
            # In the bottom pane get a COSA shell into the COSA container on the remote
            tmux                                                                                             \
                send-keys -t 0.0 "# This is an SSH shell on the remote builder" Enter';'                     \
                send-keys -t 0.0 "# You can inpect running containers with 'podman ps'" Enter';'             \
                send-keys -t 0.0 "# To directly enter the created container type:" Enter';'                  \
                send-keys -t 0.0 "#    podman exec -it \${COREOS_ASSEMBLER_REMOTE_SESSION:0:7} bash" Enter';'\
                send-keys -t 0.0 "ssh -o StrictHostKeyChecking=no -i ${CONTAINER_SSHKEY} ${REMOTEUSER}@${REMOTEHOST}" Enter';'\
                send-keys -t 0.1 "# This is a COSA shell in the remote session" Enter';'                     \
                send-keys -t 0.1 "cosa shell" Enter';'                                                       \
                send-keys -t 0.1 "arch" Enter';'
            """)
        }

        currentBuild.description = "${build_description} Ready"
        
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
}}
