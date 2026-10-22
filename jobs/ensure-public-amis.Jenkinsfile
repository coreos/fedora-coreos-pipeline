/* Restore public launch permission on production RHCOS AMIs that AWS has
   silently made private due to deprecation age, and keep AWS's six-month
   inactivity clock from expiring them again.

   AWS automatically deprecates public AMIs after 2 years and removes their
   public sharing permission after a further six months of inactivity (no
   new instances launched from the AMI). This breaks OpenShift customers
   scaling cluster nodes from older releases. Restoring the permission alone
   doesn't stop AWS from revoking it again, because the revocation condition
   is inactivity, not the permission itself: the AMI has to actually be
   launched. See coreos/coreos-assembler#4562 and Jira COS-3842.

   Only runs when clouds.aws.ensure_public is set to true in config.yaml
   (e.g. RHCOS); a no-op for FCOS.
 */

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

if (!pipecfg.clouds?.aws?.ensure_public) {
    currentBuild.result = 'NOT_BUILT'
    currentBuild.description = "skipped: clouds.aws.ensure_public is not enabled"
    return
}

cosaPod(serviceAccount: "jenkins") {
    withCredentials([file(variable: 'AWS_CONFIG_FILE',
                           credentialsId: 'aws-build-upload-config')]) {

        def job_ref = ":aws: ensure-public-amis #${env.BUILD_NUMBER} <${env.BUILD_URL}|:jenkins:> <${env.RUN_DISPLAY_URL}|:ocean:>"
        def skipped_regions = pipecfg.clouds?.aws?.skipped_regions ?: []
        def all_regions = shwrapCapture("ore aws list-regions").tokenize()
        def regions = all_regions.findAll { !(it in skipped_regions) }

        def gc_failed_regions = []
        stage("GC Stray Keepalive Instances") {
            // Catches instances that leaked from a previous run killed or
            // crashed before its own signal/defer cleanup ran.
            // `ore aws gc` only touches resources tagged with CreatedBy=mantle
            for (region in regions) {
                def gc_status = sh(script: "ore aws --region '${region}' gc --duration 1h", returnStatus: true)
                if (gc_status != 0) {
                    gc_failed_regions += region
                }
            }
        }

        def failed_regions = []
        stage("Restore Public Permissions") {
            for (region in regions) {
                def ensure_public_status = sh(script: "ore aws --region '${region}' ensure-public", returnStatus: true)
                if (ensure_public_status != 0) {
                    failed_regions += region
                }
            }
        }

        def slack_message = ""
        if (gc_failed_regions) {
            warn("Failed to GC stray instances in regions: ${gc_failed_regions.join(', ')}")
            slack_message += "${job_ref} failed to GC stray instances in regions: ${gc_failed_regions.join(', ')}"
        }
        if (failed_regions) {
            warn("Failed to restore public AMIs in regions: ${failed_regions.join(', ')}")
            if (slack_message != "") {
                slack_message += "\n"
            }
            slack_message += "${job_ref} failed to restore public AMIs in regions: ${failed_regions.join(', ')}"
        }
        if (slack_message != "") {
            pipeutils.trySlackSend(message: slack_message)
        }
    }
}
