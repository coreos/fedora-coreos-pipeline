import org.yaml.snakeyaml.Yaml;

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
             description: 'CoreOS stream to build'),
      string(name: 'VERSION',
             description: 'CoreOS version to release',
             defaultValue: '',
             trim: true),

    parameters {
        string(name: 'STREAM', 
        description: 'Stream name for RHCOS')
        string(name: 'BUILD_VERSION', description: 'Build version for RHCOS')
    }

    environment {
        COSA_IMAGE = "quay.io/coreos-assembler/coreos-assembler:rhcos-${params.STREAM}"
        INSTALLER_REPO = "https://github.com/openshift/installer"
        WORKSPACE_DIR = "workspace/installer"
    }

    stages {
        stage('Clone Installer Repo') {
            steps {
                sh "rm -rf ${WORKSPACE_DIR}"
                sh "git clone ${INSTALLER_REPO} ${WORKSPACE_DIR}"
            }
        }

        stage('Retrieve Bootimage Bump Bug Info') {
            steps {
                script {
                    def jiraToken = env.JIRA_TOKEN
                    def jiraUrl = "https://issues.redhat.com/rest/api/2/search"
                    def query = "jql=project=OCPBUGS AND component=RHCOS AND status!=Closed"

                    def jiraResponse = sh(script: "curl -s -H 'Authorization: Bearer ${jiraToken}' -H 'Content-Type: application/json' '${jiraUrl}?${query}'", returnStdout: true)
                    def jiraData = readJSON text: jiraResponse

                    if (jiraData.issues.size() > 0) {
                        def issue = jiraData.issues[0]
                        env.JIRA_BUG_ID = issue.key
                        env.BLOCK_BUGS_LIST = issue.fields.blocks.join(',')
                    } else {
                        error "No JIRA issues found matching the criteria."
                    }
                }
            }
        }

        stage('Run Cloud-Replicate Job') {
            steps {
                build job: 'cloud-replicate', parameters: [
                    string(name: 'STREAM', value: params.STREAM),
                    string(name: 'BUILD_VERSION', value: params.BUILD_VERSION)
                ]
            }
        }

        stage('Create PR for Bootimage Metadata') {
            steps {
                sh "docker run --rm -v $(pwd):/srv --workdir /srv/installer ${COSA_IMAGE} \\
                    plume cosa2stream \\
                    --target data/data/coreos/rhcos.json \\
                    --distro rhcos \\
                    --no-signatures \\
                    --name ${params.STREAM} \\
                    --url https://rhcos.mirror.openshift.com/art/storage/prod/streams \\
                    x86_64=${params.BUILD_VERSION} \\
                    aarch64=${params.BUILD_VERSION} \\
                    s390x=${params.BUILD_VERSION} \\
                    ppc64le=${params.BUILD_VERSION}"

                dir(WORKSPACE_DIR) {
                    sh "git checkout -b bump-bootimage-${params.STREAM}"
                    sh "git add data/data/coreos/rhcos.json"
                    sh "git commit -m 'OCPBUG-${env.JIRA_BUG_ID}: Bump RHCOS bootimage metadata'"
                    sh "git push origin bump-bootimage-${params.STREAM}"
                }

                script {
                    def prUrl = sh(script: "hub pull-request -f -b openshift:master -m 'OCPBUG-${env.JIRA_BUG_ID}: Bump RHCOS bootimage metadata'", returnStdout: true).trim()
                    echo "Pull Request created: ${prUrl}"
                }
            }
        }
    }

    post {
        success {
            echo "Bootimage bump job completed successfully!"
        }
        failure {
            echo "Bootimage bump job failed! Check the logs for details."
        }
    }
])

