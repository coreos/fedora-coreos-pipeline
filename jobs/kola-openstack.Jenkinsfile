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
      string(name: 'KOLA_TESTS',
             description: 'Override tests to run',
             defaultValue: "",
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

def stream_info = pipecfg.streams[params.STREAM]

// Use ca-ymq-1 for everything right now since we're having trouble with
// image uploads to ams1.
def region = "ca-ymq-1"

def s3_stream_dir = pipeutils.get_s3_streams_dir(pipecfg, params.STREAM)

// Go with a higher memory request here because we download/decompress/upload the image
def cosa_memory_request_mb = 1792

// Locking so we only run max of 2 runs (1 x86_64 and 1 aarch64) at any given time.
lock(resource: "kola-openstack-${params.ARCH}") {
    timeout(time: 90, unit: 'MINUTES') {
    cosaPod(memory: "${cosa_memory_request_mb}Mi", kvm: false,
            image: params.COREOS_ASSEMBLER_IMAGE,
            serviceAccount: "jenkins") {
    try {

        def openstack_image_name, openstack_image_filepath
        stage('Fetch Metadata/Image') {
            def commitopt = ''
            if (params.SRC_CONFIG_COMMIT != '') {
                commitopt = "--commit=${params.SRC_CONFIG_COMMIT}"
            }
            // Grab the metadata. Also grab the image so we can upload it.
            withCredentials([file(variable: 'AWS_CONFIG_FILE',
                                  credentialsId: 'aws-build-upload-config')]) {
                def ref = pipeutils.get_source_config_ref_for_stream(pipecfg, params.STREAM)
                def variant = stream_info.variant ? "--variant ${stream_info.variant}" : ""
                shwrap("""
                cosa init --branch ${ref} ${commitopt} ${variant} ${pipecfg.source_config.url}
                cosa buildfetch --build=${params.VERSION} --arch=${params.ARCH} \
                    --url=s3://${s3_stream_dir}/builds --artifact=openstack
                """)
                pipeutils.withXzMemLimit(cosa_memory_request_mb - 512) {
                    shwrap("cosa decompress --build=${params.VERSION} --artifact=openstack")
                }
                openstack_image_filepath = shwrapCapture("""
                cosa meta --build=${params.VERSION} --arch=${params.ARCH} --image-path openstack
                """)
            }

            // Use a consistent image name for this stream in case it gets left behind
            openstack_image_name = "kola-fedora-coreos-${params.STREAM}-${params.ARCH}"

        }

        withCredentials([file(variable: 'OPENSTACK_KOLA_TESTS_CONFIG',
                              credentialsId: 'openstack-kola-tests-config')]) {

            stage('Upload/Create Image') {
                // Create the image in OpenStack
                shwrap("""
                # First delete it if it currently exists, then create it.
                ore openstack --config-file=\${OPENSTACK_KOLA_TESTS_CONFIG} \
                    --region=${region} \
                    delete-image --id=${openstack_image_name} || true
                ore openstack --config-file=\${OPENSTACK_KOLA_TESTS_CONFIG} \
                    --region=${region} \
                    create-image --file=${openstack_image_filepath} \
                    --name=${openstack_image_name} --arch=${params.ARCH}
                """)
            }
        
            // In VexxHost we'll use the network called "private" for the
            // instance NIC, attach a floating IP from the "public" network and
            // use the v3-starter-4 instance (ram=8GiB, CPUs=4).
            // The clouds.yaml config should be located at $OPENSTACK_KOLA_TESTS_CONFIG.
            //
            // Since we don't have permanent images uploaded to VexxHost we'll
            // skip the upgrade test.
            try {
                kola(cosaDir: env.WORKSPACE, parallel: 5,
                     build: params.VERSION, arch: params.ARCH,
                     extraArgs: params.KOLA_TESTS,
                     skipUpgrade: true,
                     platformArgs: """-p=openstack                               \
                         --allow-rerun-success                                   \
                         --openstack-config-file=\${OPENSTACK_KOLA_TESTS_CONFIG} \
                         --openstack-flavor=v3-starter-4                         \
                         --openstack-network=private                             \
                         --openstack-region=${region}                            \
                         --openstack-floating-ip-network=public                  \
                         --openstack-image=${openstack_image_name}""")
            } finally {
                stage('Delete Image') {
                    // Delete the image in OpenStack
                    shwrap("""
                    ore openstack --config-file=\${OPENSTACK_KOLA_TESTS_CONFIG} \
                        --region=${region} delete-image --id=${openstack_image_name}
                    """)
                }
            }

        }

        currentBuild.result = 'SUCCESS'

    } catch (e) {
        currentBuild.result = 'FAILURE'
        throw e
    } finally {
        if (currentBuild.result != 'SUCCESS') {
            pipeutils.trySlackSend(message: ":openstack: kola-openstack <${env.BUILD_URL}|#${env.BUILD_NUMBER}> [${params.STREAM}][${params.ARCH}] (${params.VERSION})")
        }
    }
}}} // cosaPod, timeout, and lock finish here
