@Library('github.com/coreos/coreos-ci-lib@main') _

def streams
node {
    checkout scm
    streams = load("streams.groovy")
}

properties([
    // we only want to run one test at a time so we don't hit VexxHost resource limits
    disableConcurrentBuilds(),
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
    ]),
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
            secrets: ["aws-fcos-builds-bot-config", "openstack-kola-tests-config"]) {

        def openstack_image_filename, openstack_image_name, openstack_image_sha256, openstack_image_filepath
        stage('Fetch Metadata/Image') {
            shwrap("""
            export AWS_CONFIG_FILE=\${AWS_FCOS_BUILDS_BOT_CONFIG}/config
            cosa init --branch ${params.STREAM} https://github.com/coreos/fedora-coreos-config
            cosa buildprep --build=${params.VERSION} --arch=${params.ARCH} s3://${s3_stream_dir}/builds
            """)

            def meta = readJSON file: "builds/${params.VERSION}/${params.ARCH}/meta.json"
            if (meta.images.openstack) {
                openstack_image_filename = meta.images.openstack.path
                openstack_image_sha256 = meta.images.openstack.sha256
                openstack_image_filepath = "builds/${params.VERSION}/${params.ARCH}/${openstack_image_filename}"
            } else {
                throw new Exception("No OpenStack artifacts found in metadata for ${params.VERSION}/${params.ARCH}")
            }

            // Copy down the openstack image from S3, verify, uncompress
            // In the future maybe we can use `cosa fetch-upstream` here instead.
            // https://github.com/coreos/coreos-assembler/issues/1508
            shwrap("""
            export AWS_CONFIG_FILE=\${AWS_FCOS_BUILDS_BOT_CONFIG}/config
            aws s3 cp --no-progress s3://${s3_stream_dir}/${openstack_image_filepath} ${openstack_image_filepath}
            echo "${openstack_image_sha256} ${openstack_image_filepath}" | sha256sum --check
            unxz ${openstack_image_filepath}
            """)
            // Remove '.xz` from the end of the filename in the file path
            openstack_image_filepath = openstack_image_filepath[0..-4]
            // Use a consistent image name for this stream in case it gets left behind
            openstack_image_name = "kola-fedora-coreos-${params.STREAM}-${params.ARCH}"

        }

        stage('Upload/Create Image') {
            // Create the image in OpenStack
            shwrap("""
            # First delete it if it currently exists, then create it.
            ore openstack --config-file=\${OPENSTACK_KOLA_TESTS_CONFIG}/config \
                 delete-image --id=${openstack_image_name} || true
            ore openstack --config-file=\${OPENSTACK_KOLA_TESTS_CONFIG}/config \
                 create-image --file=${openstack_image_filepath} \
                 --name=${openstack_image_name} --arch=${params.ARCH}
            """)
        }
        
        // In VexxHost we'll use the network called "private" for the
        // instance NIC, attach a floating IP from the "public" network and
        // use the v3-starter-4 instance (ram=8GiB, CPUs=4).
        // The clouds.yaml config should be located at ${OPENSTACK_KOLA_TESTS_CONFIG}/config.
        //
        // Since we don't have permanent images uploaded to VexxHost we'll
        // skip the upgrade test.
        try {
            fcosKola(cosaDir: env.WORKSPACE, parallel: 5,
                     build: params.VERSION, arch: params.ARCH,
                     extraArgs: params.KOLA_TESTS,
                     skipBasicScenarios: true,
                     skipUpgrade: true,
                     platformArgs: """-p=openstack                               \
                        --openstack-config-file=\${OPENSTACK_KOLA_TESTS_CONFIG}/config \
                        --openstack-flavor=v3-starter-4                          \
                        --openstack-network=private                              \
                        --openstack-floating-ip-network=public                   \
                        --openstack-image=${openstack_image_name}""")
        } finally {
            stage('Delete Image') {
                // Delete the image in OpenStack
                shwrap("""
                ore openstack --config-file=\${OPENSTACK_KOLA_TESTS_CONFIG}/config \
                     delete-image --id=${openstack_image_name}
                """)
            }
        }

        currentBuild.result = 'SUCCESS'
    }
}} catch (e) {
    currentBuild.result = 'FAILURE'
    throw e
} finally {
    if (currentBuild.result != 'SUCCESS') {
        slackSend(color: 'danger', message: ":fcos: :openstack: :trashfire: kola-openstack <${env.BUILD_URL}|#${env.BUILD_NUMBER}> [${params.STREAM}][params.ARCH]")
    }
}
