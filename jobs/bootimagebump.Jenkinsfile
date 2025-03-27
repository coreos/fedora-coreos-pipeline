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
            string(name: 'DISTRO',
                   description: 'Distribution to use',
                   defaultValue: "rhcos",
                   trim: true),
            string(name: 'URL',
                   description: 'URL to use',
                   defaultValue: "https://rhcos.mirror.openshift.com/art/storage/prod/streams",
                   trim: true),
        ]),
        buildDiscarder(logRotator(
            numToKeepStr: '100',
            artifactNumToKeepStr: '100'
        )),
        durabilityHint('PERFORMANCE_OPTIMIZED')
    ])

    RHCOS_METADATA_FILE = "data/data/coreos/rhcos.json"
    PR_BRANCH = "bootimage-bump-${params.BUILD_VERSION}"
    streamSplit = params.STREAM.split('-')
    if (params.STREAM.startsWith("rhel")) {
        RELEASE_BRANCH = "release-${streamSplit[1]}" // this extracts from the rhel format
    } else {
        RELEASE_BRANCH = "release-${streamSplit[0]}" // this extracts from the RHCOS format
    }
    RELEASE_BRANCH = "release-${params.STREAM.split('-')[0]}"

    cosaPod(serviceAccount: "jenkins",
            image: params.COREOS_ASSEMBLER_IMAGE,
            memory: "512Mi", kvm: false,){
        try {
            shwrap("""
                git config --global user.name "CoreOS Bot"
                git config --global user.email "coreosbot-releng@fedoraproject.org"
            """)
            // Clone the openshift/installer repository and fetch the required release branch
            stage('Setup workspace') {
                shwrap("""
                        git clone --depth=1 --branch main https://github.com/${releng_installer}.git
                        cd installer
                        git remote add upstream https://github.com/openshift/installer.git
                        git fetch upstream ${RELEASE_BRANCH} --depth 1
                        git checkout -b ${PR_BRANCH} upstream/${RELEASE_BRANCH}
                """)
            }

            // Run plume cosa2stream to update the RHCOS bootimage metadata (rhcos.json)
            stage('Bump Bootimage Metadata') {
                shwrap("""
                        cd installer
                        plume cosa2stream \
                            --target ${RHCOS_METADATA_FILE} \
                            --distro ${params.DISTRO} \
                            --no-signatures \
                            --name ${params.STREAM} \
                            --url ${params.URL} \
                            x86_64=${params.BUILD_VERSION} \
                            aarch64=${params.BUILD_VERSION} \
                            s390x=${params.BUILD_VERSION} \
                            ppc64le=${params.BUILD_VERSION}
                """)
            }

            // Commit the updated metadata.
            stage('Create Pull Request') {
                //if (shwrap("git -C installer diff --exit-code") != 0){
                    pr_title = "${params.BOOTIMAGE_BUG_ID}: Update RHCOS-${RELEASE_BRANCH} bootimage metadata to ${params.BUILD_VERSION}"
                    commit_message = """
Update RHCOS ${RELEASE_BRANCH} bootimage metadata to ${params.BUILD_VERSION}

The changes done here will update the RHCOS ${RELEASE_BRANCH} bootimage metadata and address the following issues:

${params.JIRA_ISSUES}

This change was generated using:

```
plume cosa2stream --target ${RHCOS_METADATA_FILE}                 \\
    --distro ${params.DISTRO} --no-signatures --name ${params.STREAM}                     \\
    --url ${params.URL}  \\
    x86_64=${params.BUILD_VERSION}                                       \\
    aarch64=${params.BUILD_VERSION}                                      \\
    s390x=${params.BUILD_VERSION}                                        \\
    ppc64le=${params.BUILD_VERSION}

```
                    """.stripMargin()
                    shwrap ("""
                            cd installer
                            git add ${RHCOS_METADATA_FILE}
                            git commit -m '${commit_message}'
                    """)

                    withCredentials([usernamePassword(credentialsId: botCreds,
                                                  usernameVariable: 'GHUSER',
                                                  passwordVariable: 'GHTOKEN')]) {
                        shwrap("""
                                cd installer
                                git push -f https://\${GHUSER}:\${GHTOKEN}@github.com/${releng_installer} ${PR_BRANCH}
                                curl -H "Authorization: token \${GHTOKEN}" \\
                                     -X POST \\
                                     -d '{
                                     "title": "${pr_title}",
                                     "head": "coreosbot-releng:${PR_BRANCH}",
                                     "base": "${RELEASE_BRANCH}",
                                     "body": "${commit_message.replace("\n", "\\n").replace("\"", "\\\"")}"
                                     }' \\
                                    https://api.github.com/repos/openshift/installer/pulls --fail
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
