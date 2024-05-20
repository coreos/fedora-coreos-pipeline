/* bump-jenkins-job automates the process of checking for updates to Jenkins plugins, updating the plugin versions in a configuration file, 
pushing the changes to a Git repository, and opening a pull request for review.

Plugin Update Process:

This job reads a list of plugins from the file plugins.txt in the repository.
It iterates over each plugin, checks for updates and if a newer version is available, it updates the version in the file.
For each plugin, it fetches the latest version by querying a URL based on the Jenkins plugin repository structure.
If an update is found, it modifies the plugins.txt file to reflect the new version.
The updates in the plugins.txt file are committed and pushed to the pr_branch.
It also opens a pull request with the updated plugin versions. */

repo = "coreos/fedora-coreos-pipeline"
fork_repo = "coreosbot-releng/fedora-coreos-pipeline"
botCreds = "github-coreosbot-releng-token-username-password"
pr_branch = "JenkinsPluginsUpdate"

/* Function to extract the plugin version from the plugin URL */
def getVersionFromPluginUrl(pluginUrl) {
    /* example url : https://updates.jenkins.io/download/plugins/${pluginName}/latest/${pluginName}.hpi */
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
        branches: [[name: "main"]],
        userRemoteConfigs: [[url: "https://github.com/${fork_repo}.git"]],
        extensions: [[$class: 'WipeWorkspace']]
    ]
    pipeutils = load("utils.groovy")

    properties([
        pipelineTriggers([
            /* Schedule to check once a month */
            cron('H H 1 * *')
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
            /* Clone the repository and switch to the 'main' branch */
            shwrapCapture("""
                git clone --depth=1 --branch main https://github.com/${fork_repo}.git
            """)
            /* Read the plugins from the lockfile */
            pluginslist = shwrapCapture("grep -v ^# ${plugins_lockfile}").split('\n')
        }

        stage("Check for plugin updates") {
            def pluginUrl
            def pluginsUpdateList = []
            pluginslist.each { plugin ->
                def parts = plugin.split(':')
                if (parts.size() != 2) {
                    error("Invalid plugin format: ${plugin}")
                } else {
                    def pluginName = parts[0]
                    def currentVersion = parts[1]

                    /* Retrieve the download URL for the most recent version of a Jenkins plugin from the Jenkins update center. 
                       After following all redirects, curl prints the final URL to stdout. The final URL is captured in the pluginUrl variable for further use. */
                    pluginUrl = shwrapCapture("curl -Ls -I -f -o /dev/null -w '%{url_effective}' https://updates.jenkins.io/download/plugins/${pluginName}/latest/${pluginName}.hpi")

                    def latestVersion = getVersionFromPluginUrl(pluginUrl)
                    if (latestVersion.toString() != currentVersion.toString()) {

                        /* Update the plugin version in the lockfile */
                        pluginsToUpdate["${pluginName}"] = [currentVersion, latestVersion]
                        println("Plugin: ${pluginName} current version is ${currentVersion}, it will be updated to latest version: ${latestVersion}")

                        /* Plugins to be updated are added to the Plugins Update List */
                        pluginsUpdateList.add("-e s/${pluginName}:${currentVersion}/${pluginName}:${latestVersion}/g")

                    } else {
                        println("The latest version of ${pluginName} is already installed: ${currentVersion}")
                    }
                }
            }
            /* Plugins find/replace operation */
            if (!pluginsUpdateList.isEmpty()) {
                def pluginUpdate = "sed -i " + pluginsUpdateList.join(' ')
                shwrap("""
                    cd fedora-coreos-pipeline
                    ${pluginUpdate} ${plugins_lockfile}
                """)
            }
        }

        /* Open a PR if there are plugin updates */
        stage("Open a PR") {
            if (shwrap("git diff --exit-code") != 0){
                def message = "jenkins/plugins: update to latest versions"
                shwrap("""
                    cd fedora-coreos-pipeline
                    git add ${plugins_lockfile}
                    git commit -m '${message}' -m 'Job URL: ${env.BUILD_URL}' -m 'Job definition: https://github.com/coreos/fedora-coreos-pipeline/blob/main/jobs/bump-jenkins-plugins.Jenkinsfile'
                """)
                withCredentials([usernamePassword(credentialsId: botCreds,
                                                  usernameVariable: 'GHUSER',
                                                  passwordVariable: 'GHTOKEN')]) {
                                                    shwrap("""
                                                        cd fedora-coreos-pipeline
                                                        git push -f https://\${GHUSER}:\${GHTOKEN}@github.com/${fork_repo} main:${pr_branch}
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
        def message = "bump-jenkins-plugins #${env.BUILD_NUMBER} <${env.BUILD_URL}|:jenkins:> <${env.RUN_DISPLAY_URL}|:ocean:>"
        if (currentBuild.result == 'SUCCESS') {
            currentBuild.description = "bump-jenkins-plugins ⚡"
            message = ":pr: ${message}"
        } else {
            currentBuild.description = "bump-jenkins-plugins ❌"
        }
        if (currentBuild.result != 'SUCCESS') {
            message = ":fire: ${message}"
        }
        pipeutils.trySlackSend(message: message)
    }
}
