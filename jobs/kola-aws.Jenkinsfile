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
      string(name: 'KOLA_TESTS',
             description: 'Override tests to run',
             defaultValue: "",
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

try { timeout(time: 90, unit: 'MINUTES') {
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
                cosa buildfetch --artifact=ostree --build=${params.VERSION} \
                    --arch=${params.ARCH} --url=s3://${s3_stream_dir}/builds
                """)
            }
        }

        withCredentials([file(variable: 'AWS_CONFIG_FILE',
                              credentialsId: 'aws-kola-tests-config')]) {
            // A few independent tasks that can be run in parallel
            def parallelruns = [:]

            parallelruns['Kola:Full'] = {
                fcosKola(cosaDir: env.WORKSPACE, parallel: 5,
                         build: params.VERSION, arch: params.ARCH,
                         extraArgs: params.KOLA_TESTS,
                         skipBasicScenarios: true,
                         platformArgs: '-p=aws --aws-region=us-east-1')
            }

            if (params.ARCH == "x86_64") {
                def tests = params.KOLA_TESTS
                if (tests == "") {
                    tests = "basic"
                }
                parallelruns['Kola:Xen'] = {
                    // https://github.com/coreos/fedora-coreos-tracker/issues/997
                    // Run this test on i3.large so we can also run ext.config.platforms.aws.nvme
                    // to verify access to instance storage nvme disks works
                    // https://github.com/coreos/fedora-coreos-tracker/issues/1306
                    // Also add in the ext.config.platforms.aws.assert-xen test just
                    // to sanity check we are on a Xen instance.
                    def xen_tests = tests
                    if (xen_tests == "basic") {
                        xen_tests = "basic ext.config.platforms.aws.nvme ext.config.platforms.aws.assert-xen"
                    }
                    fcosKola(cosaDir: env.WORKSPACE,
                             build: params.VERSION, arch: params.ARCH,
                             extraArgs: xen_tests,
                             skipUpgrade: true,
                             skipBasicScenarios: true,
                             marker: "kola-xen",
                             platformArgs: '-p=aws --aws-region=us-east-1 --aws-type=i3.large')
                }
                parallelruns['Kola:Intel-Ice-Lake'] = {
                    // https://github.com/coreos/fedora-coreos-tracker/issues/1004
                    fcosKola(cosaDir: env.WORKSPACE,
                             build: params.VERSION, arch: params.ARCH,
                             extraArgs: tests,
                             skipUpgrade: true,
                             skipBasicScenarios: true,
                             marker: "kola-intel-ice-lake",
                             platformArgs: '-p=aws --aws-region=us-east-1 --aws-type=m6i.large')
                }
            } else if (params.ARCH == "aarch64") {
                def tests = params.KOLA_TESTS
                if (tests == "") {
                    tests = "basic"
                }
                parallelruns['Kola:Graviton3'] = {
                    // https://aws.amazon.com/ec2/instance-types/c7g/
                    fcosKola(cosaDir: env.WORKSPACE,
                                build: params.VERSION, arch: params.ARCH,
                                extraArgs: tests,
                                skipUpgrade: true,
                                skipBasicScenarios: true,
                                marker: "kola-graviton3",
                                platformArgs: '-p=aws --aws-region=us-east-1 --aws-type=c7g.xlarge')
                }
            }

            // process this batch
            parallel parallelruns
        }

        currentBuild.result = 'SUCCESS'
    }
}} catch (e) {
    currentBuild.result = 'FAILURE'
    throw e
} finally {
    if (official && currentBuild.result != 'SUCCESS') {
        slackSend(color: 'danger', message: ":fcos: :aws: :trashfire: kola-aws <${env.BUILD_URL}|#${env.BUILD_NUMBER}> [${params.STREAM}][${params.ARCH}] (${params.VERSION})")
    }
}
