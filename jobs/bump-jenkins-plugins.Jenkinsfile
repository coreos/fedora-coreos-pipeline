fork_repo = "coreosbot-releng/fedora-coreos-pipeline"
botCreds = "github-coreosbot-releng-token-username-password"
pr_branch = "pluginsupdate"

def getVersionFromPluginUrl(pluginUrl) {
    //example url : https://updates.jenkins.io/download/plugins/${pluginName}/latest/${pluginName}.hpi
    def parts = pluginUrl.split("/")
    def pluginVersion
    if (parts.size() >= 4) {
        def groupId = parts[-3]
        pluginVersion = parts[-2]
    } else {
        error("Unable to extract plugin version from the URL.")
    }
    return pluginVersion
}

node {
    checkout scm: [
        $class: 'GitSCM',
        branches: [[name: '*/pluginsupdate']],
        userRemoteConfigs: [[url: "https://github.com/${fork_repo}.git"]],
        extensions: [[$class: 'WipeWorkspace']]
    ]
    // these are script global vars
    pipeutils = load("utils.groovy")

    properties([
        pipelineTriggers([
            // check once a month
            pollSCM('H H 1 * *')
        ]),
        buildDiscarder(logRotator(
            numToKeepStr: '100',
            artifactNumToKeepStr: '100'
        )),
        durabilityHint('PERFORMANCE_OPTIMIZED')
    ])

    try {
        shwrap("""
            git config --global user.name "CoreOS Bot"
            git config --global user.email "coreosbot-releng@fedoraproject.org"
        """)

        def pluginslist
        def pluginsToUpdate = [:]
        def plugins_lockfile = "jenkins/controller/plugins.txt"

        stage("Read plugins.txt") {
            shwrapCapture("""
                git clone --branch pluginsupdate https://github.com/${fork_repo}.git

                cd fedora-coreos-pipeline

                # Check if the branch exists
                if git ls-remote --heads --exit-code origin ${pr_branch} | grep ${pr_branch}; then
                    git checkout ${pr_branch}
                else
                    git checkout -b ${pr_branch}
                fi
            """)
            pluginslist = shwrapCapture("grep -v ^# ${plugins_lockfile}").split('\n')
        }

        stage("Check for plugin updates") {
            def pluginUrl
            pluginslist.each { plugin ->
                def parts = plugin.split(':')
                if (parts.size() == 2) {
                    def pluginName = parts[0]
                    def currentVersion = parts[1]
                    pluginUrl = shwrapCapture("curl -Ls -I -f -o /dev/null -w '%{url_effective}' https://updates.jenkins.io/download/plugins/${pluginName}/latest/${pluginName}.hpi")
                    def latestVersion = getVersionFromPluginUrl(pluginUrl)
                    if (latestVersion.toString() != currentVersion.toString()) {
                        pluginsToUpdate["${pluginName}"] = [currentVersion, latestVersion]
                        println("Plugin: ${pluginName} current version is ${currentVersion}, it will be updated to latest version: ${latestVersion}")
                        shwrap("""
                            cd fedora-coreos-pipeline
                            sed -i '/${pluginName}:/ s/${currentVersion}/${latestVersion}/g' ${plugins_lockfile}
                        """)
                    } else {
                        println("The latest version of ${pluginName} is already installed: ${currentVersion}")
                    }
                } else {
                    error("Invalid plugin format: ${plugin}")
                }
            }
        }

        stage("Open a PR") {
            if (shwrapCapture("git diff --exit-code")){
                def message = "jenkins/plugins: update to latest versions"
                shwrap("""
                    cd fedora-coreos-pipeline
                    git add jenkins/controller/plugins.txt
                    git commit -m '${message}' -m 'Job URL: ${env.BUILD_URL}' -m 'Job definition: https://github.com/coreos/fedora-coreos-pipeline/blob/main/jobs/bump-jenkins-plugins.Jenkinsfile'
                """)
                withCredentials([usernamePassword(credentialsId: botCreds,
                                                  usernameVariable: 'GHUSER',
                                                  passwordVariable: 'GHTOKEN')]) {
                                                    shwrap("""
                                                        cd fedora-coreos-pipeline
                                                        git push -f https://\${GHUSER}:\${GHTOKEN}@github.com/${fork_repo} ${pr_branch}
                                                        curl -H "Authorization: token ${GHTOKEN}" -X POST -d '{ "title": "${message}", "head": "${pr_branch}", "base": "main" }' https://api.github.com/repos/${fork_repo}/pulls
                                                    """)
                }
            }
        }
        currentBuild.result = 'SUCCESS'
    } catch (e) {
        currentBuild.result = 'FAILURE'
        throw e
    } finally {
        if (currentBuild.result == 'SUCCESS') {
            currentBuild.description = "bump-jenkins-plugins ⚡"
        } else {
            currentBuild.description = "bump-jenkins-plugins ❌"
        }
        if (currentBuild.result != 'SUCCESS') {
            message = "bump-jenkins-plugins #${env.BUILD_NUMBER} <${env.BUILD_URL}|:jenkins:> <${env.RUN_DISPLAY_URL}|:ocean:>"
            pipeutils.trySlackSend(message: message)
        }
    }
}
