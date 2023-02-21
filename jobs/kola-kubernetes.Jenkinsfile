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
             description: 'CoreOS stream to test'),
      string(name: 'VERSION',
             description: 'CoreOS Build ID to test',
             defaultValue: '',
             trim: true),
      string(name: 'ARCH',
             description: 'Target architecture',
             defaultValue: 'x86_64',
             trim: true),
      string(name: 'COREOS_ASSEMBLER_IMAGE',
             description: 'Override the coreos-assembler image to use',
             defaultValue: "quay.io/coreos-assembler/coreos-assembler:main",
             trim: true),
      string(name: 'SRC_CONFIG_COMMIT',
             description: 'The exact config repo git commit to run tests against',
             defaultValue: '',
             trim: true),
    ]),
    buildDiscarder(logRotator(
        numToKeepStr: '100',
        artifactNumToKeepStr: '100'
    )),
    durabilityHint('PERFORMANCE_OPTIMIZED')
])

currentBuild.description = "[${params.STREAM}][${params.ARCH}] - ${params.VERSION}"

def s3_stream_dir = pipeutils.get_s3_streams_dir(pipecfg, params.STREAM)

def stream_info = pipecfg.streams[params.STREAM]

timeout(time: 60, unit: 'MINUTES') {
    cosaPod(memory: "512Mi", kvm: false,
            image: params.COREOS_ASSEMBLER_IMAGE,
            serviceAccount: "jenkins") {
    try {

        stage('Fetch Metadata') {
            def commitopt = ''
            if (params.SRC_CONFIG_COMMIT != '') {
                commitopt = "--commit=${params.SRC_CONFIG_COMMIT}"
            }
            withCredentials([file(variable: 'AWS_CONFIG_FILE',
                                  credentialsId: 'aws-build-upload-config')]) {
                def ref = pipeutils.get_source_config_ref_for_stream(pipecfg, params.STREAM)
                def variant = stream_info.variant ? "--variant ${stream_info.variant}" : ""
                shwrap("""
                cosa init --branch ${ref} ${commitopt} ${variant} ${pipecfg.source_config.url}
                cosa buildfetch --build=${params.VERSION} \
                    --arch=${params.ARCH} --url=s3://${s3_stream_dir}/builds
                """)
            }
        }

        // We use AWS here to offload the cluster and because it's more
        // realistic than QEMU and it also supports aarch64.
        withCredentials([file(variable: 'AWS_CONFIG_FILE',
                              credentialsId: 'aws-kola-tests-config')]) {
            kola(cosaDir: env.WORKSPACE,
                 build: params.VERSION, arch: params.ARCH,
                 extraArgs: "--tag k8s",
                 skipUpgrade: true,
                 platformArgs: '-p=aws --aws-region=us-east-1')
        }

        currentBuild.result = 'SUCCESS'

    } catch (e) {
        currentBuild.result = 'FAILURE'
        throw e
    } finally {
        if (currentBuild.result != 'SUCCESS') {
            pipeutils.trySlackSend(message: ":k8s: kola-kubernetes <${env.BUILD_URL}|#${env.BUILD_NUMBER}> [${params.STREAM}][${params.ARCH}] (${params.VERSION})")
        }
    }
}} // cosaPod and timeout finish here
