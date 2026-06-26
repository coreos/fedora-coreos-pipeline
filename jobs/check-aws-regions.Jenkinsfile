/* Note: AWS region enablement, refer to the documentation here: [AWS Region Enablement](docs/aws-region-enable.md) */

import org.yaml.snakeyaml.Yaml;

node {
    checkout scm
    // these are script global vars
    pipeutils = load("utils.groovy")
    pipecfg = pipeutils.load_pipecfg()
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
    withCredentials([file(variable: 'AWS_CONFIG_FILE',
                           credentialsId: 'aws-build-upload-config')]) {

        def slack_message = ""
        def job_ref = ":aws: check-aws-regions #${env.BUILD_NUMBER} <${env.BUILD_URL}|:jenkins:> <${env.RUN_DISPLAY_URL}|:ocean:>"

        stage("Check Disabled Regions") {
            // Check for disabled regions
            def disabled_regions = shwrapCapture("ore aws list-regions --disabled")
            if (disabled_regions != "") {
                warn("Disabled AWS regions detected: ${disabled_regions}")
                slack_message += "${job_ref} detected disabled regions: ${disabled_regions}\n :pencil: To enable region -> <https://github.com/coreos/fedora-coreos-pipeline/blob/main/docs/aws-region-enable.md|AWS Region Enablement>"
            }
        }

        // Restore public launch permission on any production AMIs that AWS has
        // silently made private due to deprecation age. Only runs when
        // clouds.aws.ensure_public is set to true in config.yaml (e.g. RHCOS).
        if (pipecfg.clouds?.aws?.ensure_public) {
            stage("Restore Public Permissions") {
                def failed_regions = []
                def skipped_regions = pipecfg.clouds?.aws?.skipped_regions ?: []
                def all_regions = shwrapCapture("ore aws list-regions").tokenize()
                for (region in all_regions) {
                    if (region in skipped_regions) {
                        continue
                    }
                    def rc = sh(script: "ore aws --region '${region}' ensure-public", returnStatus: true)
                    if (rc != 0) {
                        failed_regions += region
                    }
                }
                if (failed_regions) {
                    def failed_str = failed_regions.join(', ')
                    warn("Failed to restore public AMIs in regions: ${failed_str}")
                    if (slack_message != "") {
                        slack_message += "\n"
                    }
                    slack_message += "${job_ref} failed to restore public AMIs in regions: ${failed_str}"
                }
            }
        }

        if (slack_message != "") {
            pipeutils.trySlackSend(message: slack_message)
        }
    }
}
