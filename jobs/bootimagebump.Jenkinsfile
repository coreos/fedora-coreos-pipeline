botCreds = "github-coreosbot-releng-token-username-password"
releng_installer = "coreosbot-releng/installer"

node {
    checkout scm: [
        $class: 'GitSCM',
        branches: [[name: "main"]],
        userRemoteConfigs: [[url: "https://github.com/${releng_installer}.git"]],
        extensions: [
            [$class: 'CloneOption', depth: 1, noTags: true, shallow: true],
            [$class: 'WipeWorkspace']]
    ]

    // these are script global vars
    properties([
        pipelineTriggers([]),
        parameters([
            string(name: 'STREAM',
             description: 'CoreOS stream to build',
             defaultValue: '4.16-9.4',
             trim: true),
            string(name: 'BUILD_VERSION',
                   description: 'RHCOS build version to use for the bump',
                   defaultValue: '416.94.202501270445-0',
                   trim: true),
            string(name: 'BOOTIMAGE_BUG_ID',
                   description: 'JIRA bug ID for the bootimage bump',
                   defaultValue: 'OCPBUGS-48762',
                   trim: true),
            text(name: 'JIRA_ISSUES',
                   description: 'JIRA issues for the bootimage bump',
                   defaultValue: '',
                   trim: true),
            string(name: 'COREOS_ASSEMBLER_IMAGE',
                   description: 'Override the coreos-assembler image to use',
                   defaultValue: "quay.io/coreos-assembler/coreos-assembler:rhcos-4.16",
                   trim: true),
        ]),
        buildDiscarder(logRotator(
            numToKeepStr: '100',
            artifactNumToKeepStr: '100'
        )),
        durabilityHint('PERFORMANCE_OPTIMIZED')
    ])

    COSA_IMAGE = "quay.io/coreos-assembler/coreos-assembler:rhcos-${params.STREAM}"
    RHCOS_METADATA_FILE = "data/data/coreos/rhcos.json"
    PR_BRANCH = "bootimage-bump-${params.BUILD_VERSION}"
    RELEASE_BRANCH = "release-${params.STREAM.split('-')[0]}"

    cosaPod(serviceAccount: "jenkins",
            image: params.COREOS_ASSEMBLER_IMAGE,
            memory: "512Mi", kvm: false,){
        try {
            shwrap("""
                git config --global user.name "CoreOS Bot"
                git config --global user.email "coreosbot-releng@fedoraproject.org"
            """)

            stage('Setup workspace') {
                echo " AR - Cloning openshift/installer repo"
                shwrap("""
                        git clone --depth=1 --branch main https://github.com/${releng_installer}.git
                        cd installer
                        git remote -v
                        git remote add upstream https://github.com/openshift/installer.git
                        retries=3
                        for i in \$(seq 1 \$retries); do
                            echo "Attempt \$i of \$retries to fetch upstream/${RELEASE_BRANCH}"
                            git fetch upstream ${RELEASE_BRANCH} && break || sleep 10
                        done
                        git checkout -b ${PR_BRANCH} upstream/${RELEASE_BRANCH}
                        git remote -v
                """)
            }

            stage('Bump Bootimage Metadata') {
                echo "AR - Run plume cosa2stream to bump RHCOS bootimage metadata"
                shwrap("""
                cd installer
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
                """)
            }

            stage('Create Pull Request') {
                        def message = "${params.BOOTIMAGE_BUG_ID}: Update RHCOS-${params.STREAM} bootimage metadata to ${params.BUILD_VERSION}"
                        echo "AR - Create PR | rel - ${RELEASE_BRANCH}"
                        shwrap ("""
                        cd installer
                        git add ${RHCOS_METADATA_FILE}
                        git commit -m '${message}
                        The changes done here will update the RHCOS ${params.STREAM} bootimage metadata and address the following issues:
                        ${params.JIRA_ISSUES}
                        This change was generated using
                        plume cosa2stream --target data/data/coreos/rhcos.json               \\
                           --distro rhcos --no-signatures --name ${params.STREAM}       \\
                           --url https://rhcos.mirror.openshift.com/art/storage/prod/streams \\
                           x86_64=${params.BUILD_VERSION}                                    \\
                           aarch64=${params.BUILD_VERSION}                                   \\
                           s390x=${params.BUILD_VERSION}                                     \\
                           ppc64le=${params.BUILD_VERSION}'
                        """)
                        echo "git Committed"
                        withCredentials([usernamePassword(credentialsId: botCreds,
                                                      usernameVariable: 'GHUSER',
                                                      passwordVariable: 'GHTOKEN')]) {
                            echo "AR - Push begin"
                            shwrap("""
                                    cd installer
                                    git push -f https://\${GHUSER}:\${GHTOKEN}@github.com/${releng_installer} ${PR_BRANCH}
                                    curl -H "Authorization: token ${GHTOKEN}" -X POST -d '{ "title": "${message}", "head": "coreosbot-releng:${PR_BRANCH}", "base": "${RELEASE_BRANCH}" }' https://api.github.com/repos/openshift/installer/pulls --fail
                            """)
                        }
                //}
            currentBuild.result = 'SUCCESS'
            }
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
}
