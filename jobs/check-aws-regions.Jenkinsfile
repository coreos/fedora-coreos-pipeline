/* Note: AWS region enablement, refer to the documentation here: [AWS Region Enablement](docs/aws-region-enable.md) */

import org.yaml.snakeyaml.Yaml;

node {
    checkout scm
    // these are script global vars
    pipeutils = load("utils.groovy")
}

properties([
    pipelineTriggers([
        // trigger once a day
        cron('H H * * *')
    ]),
    buildDiscarder(logRotator(
        numToKeepStr: '20',
        artifactNumToKeepStr: '20'
    )),
    durabilityHint('PERFORMANCE_OPTIMIZED')
])

cosaPod(serviceAccount: "jenkins"){
    def disabled_regions = ""
    withCredentials([file(variable: 'AWS_CONFIG_FILE',
                           credentialsId: 'aws-build-upload-config')]) {
        disabled_regions = shwrapCapture("ore aws list-regions --disabled")
    }
    if (disabled_regions != "") {
        warn("Disabled AWS regions detected: ${disabled_regions}")
        pipeutils.trySlackSend(message: ":aws: check-aws-regions #${env.BUILD_NUMBER} <${env.BUILD_URL}|:jenkins:> <${env.RUN_DISPLAY_URL}|:ocean:> detected disabled regions: ${disabled_regions}\n :pencil: To enable region -> <https://github.com/coreos/fedora-coreos-pipeline/blob/main/docs/aws-region-enable.md|AWS Region Enablement>")
        return
    }    
}
