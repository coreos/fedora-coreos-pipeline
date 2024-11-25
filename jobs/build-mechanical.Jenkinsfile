node {
    checkout scm
    // these are script global vars
    pipeutils = load("utils.groovy")
    pipecfg = pipeutils.load_pipecfg()
}

properties([
    pipelineTriggers([
        // run every 24h only for now
        cron("H H * * *")
    ]),
    buildDiscarder(logRotator(
        numToKeepStr: '100',
        artifactNumToKeepStr: '100'
    )),
    durabilityHint('PERFORMANCE_OPTIMIZED')
])

node {
    def mechanical_streams = pipeutils.streams_of_type(pipecfg, 'mechanical')
    def scheduled_streams = pipeutils.scheduled_streams(pipecfg, mechanical_streams)
    if (scheduled_streams) {
        mechanical_streams = scheduled_streams
    }

    mechanical_streams.each{
        echo "Triggering build for mechanical stream: ${it}"
        build job: 'build', wait: false, parameters: [
          string(name: 'STREAM', value: it),
          booleanParam(name: 'EARLY_ARCH_JOBS', value: false)
        ]
    }
}
