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
        artifactNumToKeepStr: '30'
    )),
    durabilityHint('PERFORMANCE_OPTIMIZED')
])

currentBuild.description = "[${params.STREAM}][${params.ARCH}] - ${params.VERSION}"

def s3_stream_dir = pipeutils.get_s3_streams_dir(pipecfg, params.STREAM)

def stream_info = pipecfg.streams[params.STREAM]

cosaPod(memory: "512Mi", kvm: false,
        image: params.COREOS_ASSEMBLER_IMAGE,
        serviceAccount: "jenkins") {
    timeout(time: 30, unit: 'MINUTES') {
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
                time -v cosa buildfetch --artifact=ostree --build=${params.VERSION} \
                    --arch=${params.ARCH} --url=s3://${s3_stream_dir}/builds
                """)
            }
        }

        withCredentials([file(variable: 'GCP_KOLA_TESTS_CONFIG',
                              credentialsId: 'gcp-kola-tests-config')]) {
            // pick up the project to use from the config
            def gcp_project = shwrapCapture("jq -r .project_id \${GCP_KOLA_TESTS_CONFIG}")
            def parallelruns = [:]

            parallelruns['Kola:Full'] = {
                kola(cosaDir: env.WORKSPACE, parallel: 5,
                    build: params.VERSION, arch: params.ARCH,
                    extraArgs: params.KOLA_TESTS,
                    skipKolaTags: stream_info.skip_kola_tags,
                    platformArgs: """-p=gcp \
                        --gcp-json-key=\${GCP_KOLA_TESTS_CONFIG} \
                        --gcp-project=${gcp_project}""")
            }
            if (params.ARCH == "x86_64") {
                parallelruns['Kola:Confidential'] = {
                    def tests = params.KOLA_TESTS
                    if (tests == "") {
                        tests = "basic"
                    }
                    // https://github.com/coreos/fedora-coreos-tracker/issues/1202
                    def confidential_tests = tests
                    if (confidential_tests == "basic") {
                        confidential_tests = "basic ext.config.platforms.gcp.confidential-vm-nvme-symlink"
                    }
                    // https://github.com/coreos/coreos-assembler/issues/3556
                    kola(cosaDir: env.WORKSPACE,
                        build: params.VERSION, arch: params.ARCH,
                        extraArgs: confidential_tests,
                        skipUpgrade: true,
                        skipKolaTags: stream_info.skip_kola_tags,
                        marker: "confidential",
                        platformArgs: """-p=gcp \
                            --gcp-json-key=\${GCP_KOLA_TESTS_CONFIG} \
                            --gcp-project=${gcp_project} \
                            --gcp-confidential-type sev_snp""")
                }
            }
            
            // process this batch
            parallel parallelruns

            stage('Garbage Collection') {
                shwrap("""
                ore gcloud gc --debug        \
                    --project=${gcp_project} \
                    --json-key=\${GCP_KOLA_TESTS_CONFIG}
                """)
            }
        }

        currentBuild.result = 'SUCCESS'

    } catch (e) {
        currentBuild.result = 'FAILURE'
        throw e
    } finally {
        if (currentBuild.result != 'SUCCESS') {
            pipeutils.trySlackSend(message: ":gcp: kola-gcp #${env.BUILD_NUMBER} <${env.BUILD_URL}|:jenkins:> <${env.RUN_DISPLAY_URL}|:ocean:> [${params.STREAM}][${params.ARCH}] (${params.VERSION})")
        }
    }
}} // cosaPod and timeout finish here
