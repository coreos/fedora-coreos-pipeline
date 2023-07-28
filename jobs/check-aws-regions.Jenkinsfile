import org.yaml.snakeyaml.Yaml;

node {
    checkout scm
    // these are script global vars
    pipeutils = load("utils.groovy")
}

properties([
    pipelineTriggers([
        // check once a day
        pollSCM('H H * * *')
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
        pipeutils.trySlackSend(message: ":aws: aws-regions-disabled :jenkins:<${env.BUILD_URL}|${env.BUILD_NUMBER}> ${disabled_regions}")
        return
    }    
}
