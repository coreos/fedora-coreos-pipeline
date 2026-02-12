node {
    checkout scm
    // these are script global vars
    pipeutils = load("utils.groovy")
    pipecfg = pipeutils.load_pipecfg()
}

properties([
    pipelineTriggers([
        // run every 24h at 5:00 UTC
        cron("0 5 * * *")
    ]),
    buildDiscarder(logRotator(
        numToKeepStr: '100',
        artifactNumToKeepStr: '100'
    )),
    durabilityHint('PERFORMANCE_OPTIMIZED')
])

node {
    def development_streams = pipeutils.streams_of_type(pipecfg, 'development')

    def scheduled_streams = pipeutils.scheduled_streams(pipecfg, development_streams)
    if (scheduled_streams) {
        development_streams = scheduled_streams
    }
    // Filter out streams that are managed by konflux
    development_streams = pipeutils.non_konflux_driven_streams(pipecfg, development_streams)

    parallel development_streams.collectEntries { stream -> [stream, {
        // If there is a bump-lockfile job running for this stream
        // let's wait until it is done before we kick off the daily
        // development build so we get the latest content.
        echo "Waiting for bump-${stream} lock"
        lock(resource: "bump-${stream}") {
            echo "Triggering build for development stream: ${stream}"
            build job: 'build', wait: false, propagate: false, parameters: [
              string(name: 'STREAM', value: stream),
              booleanParam(name: 'EARLY_ARCH_JOBS', value: false)
            ]
        }
    }] }
}
