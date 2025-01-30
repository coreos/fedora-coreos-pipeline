import org.yaml.snakeyaml.Yaml;

node {
    checkout scm
    // these are script global vars
    pipeutils = load("utils.groovy")
    pipecfg = pipeutils.load_pipecfg()

    properties([
        pipelineTriggers([]),
        parameters([
            choice(name: 'STREAM',
                   choices: pipeutils.get_streams_choices(pipecfg),
                   description: 'RHCOS stream to bump'),
            string(name: 'BUILD_VERSION',
                   description: 'RHCOS build version to use for the bump',
                   defaultValue: '',
                   trim: true),
            string(name: 'BOOTIMAGE_BUG_ID',
                   description: 'JIRA bug ID for the bootimage bump',
                   defaultValue: '',
                   trim: true),
        ]),
        buildDiscarder(logRotator(
            numToKeepStr: '100',
            artifactNumToKeepStr: '100'
        )),
        durabilityHint('PERFORMANCE_OPTIMIZED')
    ])

    def build_description = "[${params.STREAM}][bootimage-bump]"

    // Reload pipecfg if a hotfix build was provided
    if (params.PIPECFG_HOTFIX_REPO || params.PIPECFG_HOTFIX_REF) {
        pipecfg = pipeutils.load_pipecfg(params.PIPECFG_HOTFIX_REPO, params.PIPECFG_HOTFIX_REF)
        build_description = "[${params.STREAM}-${pipecfg.hotfix.name}][bootimage-bump]"
    }

    // Define environment variables
    def INSTALLER_REPO = 'https://github.com/openshift/installer.git'
    def COSA_IMAGE = "quay.io/coreos-assembler/coreos-assembler:rhcos-${params.STREAM}"
    def RHCOS_METADATA_FILE = "data/data/coreos/rhcos.json"

    try {
        stage('Setup workspace') {
            echo " AR - Cloning openshift/installer repo"
            sh "git clone --depth=1 --branch main ${INSTALLER_REPO} ${env.WORKSPACE}/installer"
            dir("${env.WORKSPACE}/installer") {
                sh "git checkout -b bootimage-bump-${params.BUILD_VERSION}"
            }
        }

        stage('Bump Bootimage Metadata') {
            echo "AR - Run plume cosa2stream to bump RHCOS bootimage metadata"
            dir("${env.WORKSPACE}/installer") {
                sh """
                podman run --rm -v ${env.WORKSPACE}/installer:/workspace:z ${COSA_IMAGE} \
                plume cosa2stream \
                    --target ${RHCOS_METADATA_FILE} \
                    --distro rhcos \
                    --no-signatures \
                    --name ${params.STREAM} \
                    --url https://rhcos.mirror.openshift.com/art/storage/prod/streams \
                    x86_64=${params.BUILD_VERSION} \
                    aarch64=${params.BUILD_VERSION} \
                    s390x=${params.BUILD_VERSION} \
                    ppc64le=${params.BUILD_VERSION}
                """
            }
        }

        stage('Create Pull Request') {
            echo "AR - Create PR"
            dir("${env.WORKSPACE}/installer") {
                sh """
                git add ${RHCOS_METADATA_FILE}
                git commit -m "OCPBUGS-${params.BOOTIMAGE_BUG_ID}: Bump RHCOS bootimage to ${params.BUILD_VERSION}"
                git push origin bootimage-bump-${params.BUILD_VERSION}
                """
                // Use GitHub CLI to create the PR
                sh """
                gh pr create \
                    --title "OCPBUGS-${params.BOOTIMAGE_BUG_ID}: Bump RHCOS bootimage to ${params.BUILD_VERSION}" \
                    --body "This PR bumps the RHCOS bootimage to version ${params.BUILD_VERSION}."
                """
            }
        }

        currentBuild.result = 'SUCCESS'
    } catch (e) {
        currentBuild.result = 'FAILURE'
        throw e
    } finally {
        def message = "[${params.STREAM}][bootimage-bump] #${env.BUILD_NUMBER} <${env.BUILD_URL}|:jenkins:> <${env.RUN_DISPLAY_URL}|:ocean:>"

        if (currentBuild.result == 'SUCCESS') {
            message = ":sparkles: ${message}"
        } else if (currentBuild.result == 'UNSTABLE') {
            message = ":warning: ${message}"
        } else {
            message = ":fire: ${message}"
        }

        echo message
        //pipeutils.trySlackSend(message: message)
    }
}
