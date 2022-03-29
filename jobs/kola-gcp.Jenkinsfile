def pipeutils, streams, official
node {
    checkout scm
    pipeutils = load("utils.groovy")
    streams = load("streams.groovy")
    official = pipeutils.isOfficial()
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
      string(name: 'KOLA_TESTS',
             description: 'Override tests to run',
             defaultValue: "",
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

try { timeout(time: 30, unit: 'MINUTES') {
    cosaPod(image: params.COREOS_ASSEMBLER_IMAGE,
            memory: "256Mi", kvm: false,
            secrets: ["aws-fcos-builds-bot-config", "gcp-kola-tests-config"]) {

        def gcp_project
        stage('Fetch Metadata') {
            def commitopt = ''
            if (params.FCOS_CONFIG_COMMIT != '') {
                commitopt = "--commit=${params.FCOS_CONFIG_COMMIT}"
            }
            shwrap("""
            export AWS_CONFIG_FILE=\${AWS_FCOS_BUILDS_BOT_CONFIG}/config
            cosa init --branch ${params.STREAM} ${commitopt} https://github.com/coreos/fedora-coreos-config
            cosa buildfetch --artifact=ostree --build=${params.VERSION} \
                --arch=${params.ARCH} --url=s3://${s3_stream_dir}/builds
            """)

            // pick up the project to use from the config
            gcp_project = shwrapCapture("jq -r .project_id \${GCP_KOLA_TESTS_CONFIG}/config")
        }

        fcosKola(cosaDir: env.WORKSPACE, parallel: 5,
                 build: params.VERSION, arch: params.ARCH,
                 extraArgs: params.KOLA_TESTS,
                 skipBasicScenarios: true,
                 platformArgs: """-p=gce \
                    --gce-json-key=\${GCP_KOLA_TESTS_CONFIG}/config \
                    --gce-project=${gcp_project}""")

        currentBuild.result = 'SUCCESS'
    }
}} catch (e) {
    currentBuild.result = 'FAILURE'
    throw e
} finally {
    if (official && currentBuild.result != 'SUCCESS') {
        slackSend(color: 'danger', message: ":fcos: :gcp: :trashfire: kola-gcp <${env.BUILD_URL}|#${env.BUILD_NUMBER}> [${params.STREAM}][${params.ARCH}] (${params.VERSION})")
    }
}
