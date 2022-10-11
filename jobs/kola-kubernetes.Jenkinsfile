def pipeutils, pipecfg, official
node {
    checkout scm
    pipeutils = load("utils.groovy")
    pipecfg = pipeutils.load_pipecfg()
    official = pipeutils.isOfficial()
}

properties([
    pipelineTriggers([]),
    parameters([
      choice(name: 'STREAM',
             choices: pipeutils.get_streams_choices(pipecfg),
             description: 'Fedora CoreOS stream to test'),
      string(name: 'VERSION',
             description: 'Fedora CoreOS Build ID to test',
             defaultValue: '',
             trim: true),
      string(name: 'ARCH',
             description: 'Target architecture',
             defaultValue: 'x86_64',
             trim: true),
      string(name: 'S3_STREAM_DIR',
             description: 'Override the Fedora CoreOS S3 stream directory',
             defaultValue: '',
             trim: true),
      string(name: 'COREOS_ASSEMBLER_IMAGE',
             description: 'Override the coreos-assembler image to use',
             defaultValue: "coreos-assembler:main",
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

def s3_stream_dir = params.S3_STREAM_DIR
if (s3_stream_dir == "") {
    s3_stream_dir = "${pipecfg.s3_bucket}/prod/streams/${params.STREAM}"
}

try { timeout(time: 60, unit: 'MINUTES') {
    cosaPod(memory: "512Mi", kvm: false,
            image: params.COREOS_ASSEMBLER_IMAGE) {

        stage('Fetch Metadata') {
            def commitopt = ''
            if (params.SRC_CONFIG_COMMIT != '') {
                commitopt = "--commit=${params.SRC_CONFIG_COMMIT}"
            }
            withCredentials([file(variable: 'AWS_CONFIG_FILE',
                                  credentialsId: 'aws-build-upload-config')]) {
                def ref = pipeutils.get_source_config_ref_for_stream(pipecfg, params.STREAM)
                shwrap("""
                cosa init --branch ${ref} ${commitopt} ${pipecfg.source_config.url}
                cosa buildfetch --build=${params.VERSION} \
                    --arch=${params.ARCH} --url=s3://${s3_stream_dir}/builds
                """)
            }
        }

        // We use AWS here to offload the cluster and because it's more
        // realistic than QEMU and it also supports aarch64.
        withCredentials([file(variable: 'AWS_CONFIG_FILE',
                              credentialsId: 'aws-kola-tests-config')]) {
            fcosKola(cosaDir: env.WORKSPACE,
                     build: params.VERSION, arch: params.ARCH,
                     extraArgs: "--tag k8s",
                     skipUpgrade: true,
                     skipBasicScenarios: true,
                     platformArgs: '-p=aws --aws-region=us-east-1')
        }

        currentBuild.result = 'SUCCESS'
    }
}} catch (e) {
    currentBuild.result = 'FAILURE'
    throw e
} finally {
    if (official && currentBuild.result != 'SUCCESS') {
        slackSend(color: 'danger', message: ":fcos: :k8s: :trashfire: kola-kubernetes <${env.BUILD_URL}|#${env.BUILD_NUMBER}> [${params.STREAM}][${params.ARCH}] (${params.VERSION})")
    }
}
