botCreds = "github-coreosbot-releng-token-username-password"
releng_installer = "coreosbot-releng/installer"

node {
    checkout scm
    pipeutils = load("utils.groovy")

    properties([
        pipelineTriggers([]),
        parameters([
            string(name: 'STREAM',
             description: 'CoreOS stream to build',
             defaultValue: '',
             trim: true),
            string(name: 'BUILD_VERSION',
                   description: 'RHCOS build version to use for the bump',
                   defaultValue: '',
                   trim: true),
            string(name: 'SECONDARY_STREAM',
                   description: 'Secondary stream for dual-stream releases (4.22+, e.g. 4.22-9.8 if primary is rhel-10.2)',
                   defaultValue: '',
                   trim: true),
            string(name: 'SECONDARY_BUILD_VERSION',
                   description: 'Build version for the secondary stream',
                   defaultValue: '',
                   trim: true),
            string(name: 'BOOTIMAGE_BUG_ID',
                   description: 'JIRA bug ID for the bootimage bump',
                   defaultValue: '',
                   trim: true),
            text(name: 'JIRA_ISSUES',
                   description: 'JIRA issues for the bootimage bump',
                   defaultValue: '',
                   trim: true),
            string(name: 'RELEASE_BRANCH',
                   description: 'The installer release branch (e.g. release-4.22). Required only for rhel-* streams.',
                   defaultValue: '',
                   trim: true),
            string(name: 'COREOS_ASSEMBLER_IMAGE',
                   description: 'Override the coreos-assembler image to use',
                   defaultValue: "quay.io/coreos-assembler/coreos-assembler:rhcos-",
                   trim: true),
            string(name: 'DISTRO',
                   description: 'Distribution to use',
                   defaultValue: "rhcos",
                   trim: true),
            string(name: 'URL',
                   description: 'URL to use',
                   defaultValue: "https://rhcos.mirror.openshift.com/art/storage/prod/streams",
                   trim: true),
            booleanParam(name: 'RUN_CLOUD_REPLICATE',
                         defaultValue: true,
                         description: 'Run cloud-replicate job before creating the bootimage bump PR'),
        ]),
        buildDiscarder(logRotator(
            numToKeepStr: '100',
            artifactNumToKeepStr: '100'
        )),
        durabilityHint('PERFORMANCE_OPTIMIZED')
    ])

    if (params.STREAM == "") {
        throw new Exception("Missing STREAM parameter!")
    }
    if (params.BUILD_VERSION == "") {
        throw new Exception("Missing BUILD_VERSION parameter!")
    }

    PR_BRANCH = "bootimage-bump-${params.BUILD_VERSION}"
    streamSplit = params.STREAM.split('-')
    if (params.RELEASE_BRANCH != '') {
        RELEASE_BRANCH = params.RELEASE_BRANCH
    } else {
        // Auto-detect for RHCOS-format streams (e.g. 4.18-9.4 -> release-4.18)
        RELEASE_BRANCH = "release-${streamSplit[0]}"
    }

    // For OCP 4.22+, the metadata file is split by RHEL major version
    // (coreos-rhel-9.json / coreos-rhel-10.json) instead of rhcos.json
    isDualStream = params.SECONDARY_STREAM != '' && params.SECONDARY_BUILD_VERSION != ''
    def ocpMinor = RELEASE_BRANCH.tokenize('-.')[2]
    def rhelMajor = streamSplit.length > 1 ? streamSplit[1].tokenize('.')[0] : null
    if (ocpMinor && (ocpMinor as Integer) >= 22 && rhelMajor) {
        RHCOS_METADATA_FILE = "data/data/coreos/coreos-rhel-${rhelMajor}.json"
    } else {
        RHCOS_METADATA_FILE = "data/data/coreos/rhcos.json"
    }

    if (isDualStream) {
        def secondarySplit = params.SECONDARY_STREAM.split('-')
        def secondaryRhelMajor = secondarySplit.length > 1 ? secondarySplit[1].tokenize('.')[0] : null
        SECONDARY_METADATA_FILE = "data/data/coreos/coreos-rhel-${secondaryRhelMajor}.json"
    }

    // Optionally run cloud-replicate job first
    if (params.RUN_CLOUD_REPLICATE) {
        stage('Run Cloud Replicate') {
            // Check if the build exists before triggering cloud-replicate
            def buildsUrl = "${params.URL}/${params.STREAM}/builds/builds.json"
            def buildExists = sh(
                script: """
                    curl -sf "${buildsUrl}" | \
                    jq -e '.builds[] | select(.id == "${params.BUILD_VERSION}")' > /dev/null 2>&1 && echo "true" || echo "false"
                """,
                returnStdout: true
            ).trim()

            if (buildExists == "true") {
                build job: 'cloud-replicate', wait: true, parameters: [
                    string(name: 'STREAM', value: params.STREAM),
                    string(name: 'VERSION', value: params.BUILD_VERSION)
                ]
            } else {
                echo "Build ${params.BUILD_VERSION} not found in ${buildsUrl}, skipping cloud-replicate"
            }

            // For dual-stream releases (4.22+), also run cloud-replicate for the secondary stream
            if (isDualStream) {
                def secondaryBuildsUrl = "${params.URL}/${params.SECONDARY_STREAM}/builds/builds.json"
                def secondaryBuildExists = sh(
                    script: """
                        curl -sf "${secondaryBuildsUrl}" | \
                        jq -e '.builds[] | select(.id == "${params.SECONDARY_BUILD_VERSION}")' > /dev/null 2>&1 && echo "true" || echo "false"
                    """,
                    returnStdout: true
                ).trim()

                if (secondaryBuildExists == "true") {
                    build job: 'cloud-replicate', wait: true, parameters: [
                        string(name: 'STREAM', value: params.SECONDARY_STREAM),
                        string(name: 'VERSION', value: params.SECONDARY_BUILD_VERSION)
                    ]
                } else {
                    echo "Build ${params.SECONDARY_BUILD_VERSION} not found in ${secondaryBuildsUrl}, skipping cloud-replicate for secondary stream"
                }
            }
        }
    }

    cosaPod(serviceAccount: "jenkins",
            image: params.COREOS_ASSEMBLER_IMAGE,
            memory: "512Mi", kvm: false,){
        def pr_url = ""
        try {
            shwrap("""
                git config --global user.name "CoreOS Bot"
                git config --global user.email "coreosbot-releng@fedoraproject.org"
            """)
            // Clone the openshift/installer repository using sparse-checkout
            // to only fetch the bootimage metadata directory instead of all 108k files
            stage('Setup workspace') {
                shwrap("""
                        mkdir installer && cd installer
                        git init
                        git remote add origin https://github.com/${releng_installer}.git
                        git remote add upstream https://github.com/openshift/installer.git
                        git sparse-checkout init --cone
                        git sparse-checkout set data/data/coreos
                        git fetch upstream ${RELEASE_BRANCH} --depth 1 --filter=blob:none
                        git checkout -b ${PR_BRANCH} upstream/${RELEASE_BRANCH}
                """)
            }

            // Run plume cosa2stream to update the RHCOS bootimage metadata
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

                // For dual-stream releases (4.22+), also bump the secondary metadata file
                if (isDualStream) {
                    shwrap("""
                            cd installer
                            plume cosa2stream \
                                --target ${SECONDARY_METADATA_FILE} \
                                --distro ${params.DISTRO} \
                                --no-signatures \
                                --name ${params.SECONDARY_STREAM} \
                                --url ${params.URL} \
                                x86_64=${params.SECONDARY_BUILD_VERSION} \
                                aarch64=${params.SECONDARY_BUILD_VERSION} \
                                s390x=${params.SECONDARY_BUILD_VERSION} \
                                ppc64le=${params.SECONDARY_BUILD_VERSION}
                    """)
                }
            }

            // Commit the updated metadata and create PR if there are changes.
            stage('Create Pull Request') {
                if (sh(script: "git -C installer diff --exit-code", returnStatus: true) == 0) {
                    echo "No changes detected in bootimage metadata, skipping PR creation"
                    currentBuild.result = 'SUCCESS'
                    return
                }

                    def buildVersions = isDualStream ? "${params.BUILD_VERSION} / ${params.SECONDARY_BUILD_VERSION}" : params.BUILD_VERSION
                    pr_title = "${params.BOOTIMAGE_BUG_ID}: Update RHCOS-${RELEASE_BRANCH} bootimage metadata to ${buildVersions}"

                    def primary_commit_message = """
Update ${RHCOS_METADATA_FILE} to ${params.BUILD_VERSION}

${params.JIRA_ISSUES}

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
                            git commit -m '${primary_commit_message}'
                    """)

                    def secondary_commit_message = ""
                    if (isDualStream) {
                        secondary_commit_message = """
Update ${SECONDARY_METADATA_FILE} to ${params.SECONDARY_BUILD_VERSION}

${params.JIRA_ISSUES}

```
plume cosa2stream --target ${SECONDARY_METADATA_FILE}                 \\
    --distro ${params.DISTRO} --no-signatures --name ${params.SECONDARY_STREAM}                     \\
    --url ${params.URL}  \\
    x86_64=${params.SECONDARY_BUILD_VERSION}                                       \\
    aarch64=${params.SECONDARY_BUILD_VERSION}                                      \\
    s390x=${params.SECONDARY_BUILD_VERSION}                                        \\
    ppc64le=${params.SECONDARY_BUILD_VERSION}
```
                        """.stripMargin()
                        shwrap ("""
                                cd installer
                                git add ${SECONDARY_METADATA_FILE}
                                git commit -m '${secondary_commit_message}'
                        """)
                    }

                    withCredentials([usernamePassword(credentialsId: botCreds,
                                                  usernameVariable: 'GHUSER',
                                                  passwordVariable: 'GHTOKEN')]) {
                        shwrap("""
                                cd installer
                                git push -f https://\${GHUSER}:\${GHTOKEN}@github.com/${releng_installer} ${PR_BRANCH}
                        """)
                        def prResponse = shwrapCapture("""
                                curl -H "Authorization: token \${GHTOKEN}" \\
                                     -X POST \\
                                     -d '{
                                     "title": "${pr_title}",
                                     "head": "coreosbot-releng:${PR_BRANCH}",
                                     "base": "${RELEASE_BRANCH}",
                                     "body": "${(primary_commit_message + secondary_commit_message).replace("\n", "\\n").replace("\"", "\\\"")}"
                                     }' \\
                                    https://api.github.com/repos/openshift/installer/pulls --fail
                        """)
                        def prJson = readJSON(text: prResponse)
                        pr_url = prJson.html_url
                    }
            currentBuild.result = 'SUCCESS'
            }
        } catch (e) {
            currentBuild.result = 'FAILURE'
            throw e
        } finally {
            def streamLabel = isDualStream ? "${params.STREAM} + ${params.SECONDARY_STREAM}" : params.STREAM
            def message = "[${streamLabel}][bootimage-bump] #${env.BUILD_NUMBER} <${env.BUILD_URL}|:jenkins:> <${env.RUN_DISPLAY_URL}|:ocean:>"
            if (currentBuild.result == 'SUCCESS') {
                message = ":sparkles: ${message} <${pr_url}|:pr:>"
            } else if (currentBuild.result == 'UNSTABLE') {
                message = ":warning: ${message}"
            } else {
                message = ":fire: ${message}"
            }
            echo message
            pipeutils.trySlackSend(message: message)
        }
    }
}
