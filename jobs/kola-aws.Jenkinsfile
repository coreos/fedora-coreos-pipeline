@Library('github.com/coreos/coreos-ci-lib@main') _

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
             trim: true)
    ])
])

currentBuild.description = "[${params.STREAM}][${params.ARCH}] - ${params.VERSION}"

def s3_stream_dir = params.S3_STREAM_DIR
if (s3_stream_dir == "") {
    s3_stream_dir = "fcos-builds/prod/streams/${params.STREAM}"
}

cosaPod(image: params.COREOS_ASSEMBLER_IMAGE,
        memory: "256Mi", kvm: false,
        secrets: ["aws-fcos-builds-bot-config", "aws-fcos-kola-bot-config"]) {

    def ami, ami_region, instance_type_arg
    stage('Fetch Metadata') {
        shwrap("""
        export AWS_CONFIG_FILE=\${AWS_FCOS_BUILDS_BOT_CONFIG}/config
        cosa init --branch ${params.STREAM} https://github.com/coreos/fedora-coreos-config
        cosa buildprep --ostree --build=${params.VERSION} --arch=${params.ARCH} s3://${s3_stream_dir}/builds
        """)

        def meta = readJSON file: "builds/${params.VERSION}/${params.ARCH}/meta.json"
        if (meta.amis.size() > 0) {
            ami = meta['amis'][0]['hvm']
            ami_region = meta['amis'][0]['name']
        } else {
            throw new Exception("No AMI found in metadata for ${params.VERSION}")
        }

        // Need to override the instance type if we're AARCH64
        instance_type_arg = ""
        if (params.ARCH == "aarch64") {
            instance_type_arg = "--aws-type=c6g.xlarge"
        }
    }

    fcosKola(cosaDir: env.WORKSPACE, parallel: 5, build: params.VERSION,
             extraArgs: params.KOLA_TESTS,
             platformArgs: """-p=aws \
                --aws-credentials-file=\${AWS_FCOS_KOLA_BOT_CONFIG}/config \
                --aws-ami=${ami} \
                --aws-region=${ami_region} \
                ${instance_type_arg}""")
}
