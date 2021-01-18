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
      string(name: 'KOLA_TESTS',
             description: 'Override tests to run',
             defaultValue: "",
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

cosaPod(image: params.COREOS_ASSEMBLER_IMAGE,
        memory: "256Mi", kvm: false,
        secrets: ["aws-fcos-builds-bot-config", "gcp-kola-tests-config"]) {

    def gcp_image, gcp_image_project, gcp_project
    stage('Fetch Metadata') {
        shwrap("""
        export AWS_CONFIG_FILE=\${AWS_FCOS_BUILDS_BOT_CONFIG}/config
        cosa init --branch ${params.STREAM} https://github.com/coreos/fedora-coreos-config
        cosa buildprep --ostree --build=${params.VERSION} s3://${s3_stream_dir}/builds
        """)

        def basearch = shwrapCapture("cosa basearch")
        def meta = readJSON file: "builds/${params.VERSION}/${basearch}/meta.json"
        if (meta.gcp.image) {
            gcp_image = meta.gcp.image
            gcp_image_project = meta.gcp.project
        } else {
            throw new Exception("No GCP image found in metadata for ${params.VERSION}")
        }

        // pick up the project to use from the config
        gcp_project = shwrapCapture("jq -r .project_id \${GCP_KOLA_TESTS_CONFIG}/config")
    }

    fcosKola(cosaDir: env.WORKSPACE, parallel: 5, build: params.VERSION,
             extraArgs: params.KOLA_TESTS,
             platformArgs: """-p=gce \
                --gce-json-key=\${GCP_KOLA_TESTS_CONFIG}/config \
                --gce-project=${gcp_project} \
                --gce-image=projects/${gcp_image_project}/global/images/${gcp_image}""")
}
