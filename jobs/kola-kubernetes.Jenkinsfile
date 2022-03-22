def pipeutils, streams
def notify_slack
node {
    checkout scm
    pipeutils = load("utils.groovy")
    streams = load("streams.groovy")
    def pipecfg = pipeutils.load_config()
    notify_slack = pipecfg['notify-slack']
}

properties([
    pipelineTriggers([]),
    parameters([
      choice(name: 'STREAM',
             // list devel first so that it's the default choice
             choices: (streams.development + streams.production + streams.mechanical),
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
      string(name: 'FCOS_CONFIG_COMMIT',
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
    s3_stream_dir = "fcos-builds/prod/streams/${params.STREAM}"
}

try { timeout(time: 60, unit: 'MINUTES') {
    cosaPod(image: params.COREOS_ASSEMBLER_IMAGE,
            memory: "256Mi", kvm: false,
            secrets: ["aws-fcos-builds-bot-config", "aws-fcos-kola-bot-config"]) {

        stage('Fetch Metadata') {
            def commitopt = ''
            if (params.FCOS_CONFIG_COMMIT != '') {
                commitopt = "--commit=${params.FCOS_CONFIG_COMMIT}"
            }
            shwrap("""
            export AWS_CONFIG_FILE=\${AWS_FCOS_BUILDS_BOT_CONFIG}/config
            cosa init --branch ${params.STREAM} ${commitopt} https://github.com/coreos/fedora-coreos-config
            cosa buildfetch --build=${params.VERSION} \
                --arch=${params.ARCH} --url=s3://${s3_stream_dir}/builds
            """)
        }

        // We use AWS here to offload the cluster and because it's more
        // realistic than QEMU and it also supports aarch64.
        fcosKola(cosaDir: env.WORKSPACE,
                 build: params.VERSION, arch: params.ARCH,
                 extraArgs: "--tag k8s",
                 skipUpgrade: true,
                 skipBasicScenarios: true,
                 platformArgs: """-p=aws \
                    --aws-credentials-file=\${AWS_FCOS_KOLA_BOT_CONFIG}/config \
                    --aws-region=us-east-1""")

        currentBuild.result = 'SUCCESS'
    }
}} catch (e) {
    currentBuild.result = 'FAILURE'
    throw e
} finally {
    if (currentBuild.result != 'SUCCESS' && notify_slack == "yes") {
        slackSend(color: 'danger', message: ":fcos: :k8s: :trashfire: kola-kubernetes <${env.BUILD_URL}|#${env.BUILD_NUMBER}> [${params.STREAM}][${params.ARCH}] (${params.VERSION})")
    }
}
