@Library('github.com/coreos/coreos-ci-lib') _

def streams
node {
    checkout scm
    streams = load("streams.groovy")
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
      string(name: 'S3_STREAM_DIR',
             description: 'Override the Fedora CoreOS S3 stream directory',
             defaultValue: '',
             trim: true),
      string(name: 'COREOS_ASSEMBLER_IMAGE',
             description: 'Override the coreos-assembler image to use',
             defaultValue: "coreos-assembler:master",
             trim: true)
    ])
])

currentBuild.description = "[${params.STREAM}] - ${params.VERSION}"

def s3_stream_dir = params.S3_STREAM_DIR
if (s3_stream_dir == "") {
    s3_stream_dir = "fcos-builds/prod/streams/${params.STREAM}"
}

cosaPod(image: params.COREOS_ASSEMBLER_IMAGE, memory: "256Mi",
        secrets: ["aws-fcos-builds-bot-config", "aws-fcos-kola-bot-config"]) {

    def ami, ami_region
    stage('Fetch Metadata') {
        shwrap("""
        export AWS_CONFIG_FILE=${AWS_FCOS_BUILDS_BOT_CONFIG}/config
        cosa init --branch ${params.STREAM} https://github.com/coreos/fedora-coreos-config
        cosa buildprep --ostree --build=${params.VERSION} s3://${s3_stream_dir}/builds
        """)

        def basearch = shwrapCapture("cosa basearch")
        def meta = readJSON file: "builds/${params.VERSION}/${basearch}/meta.json"
        if (meta.amis.size() > 0) {
            ami = meta['amis'][0]['hvm']
            ami_region = meta['amis'][0]['name']
        } else {
            throw new Exception("No AMI found in metadata for ${params.VERSION}")
        }
    }

    fcosKola(cosaDir: env.WORKSPACE, parallel: 5, build: params.VERSION,
             platformArgs: """-p aws \
                --aws-credentials-file ${AWS_FCOS_KOLA_BOT_CONFIG}/config \
                --aws-ami ${ami} \
                --aws-region ${ami_region}""")
}
