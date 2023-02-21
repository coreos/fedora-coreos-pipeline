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
    durabilityHint('PERFORMANCE_OPTIMIZED')
])

node {
    def mechanical_streams = pipeutils.streams_of_type(pipecfg, 'mechanical')

    change = checkout(
        [$class: 'GitSCM',
         userRemoteConfigs: [[url: pipecfg.source_config.url]],
         branches: pipeutils.streams_as_branches(mechanical_streams)
        ]
    )

    if (pipeutils.triggered_by_push()) {
        stream = pipeutils.stream_from_branch(change.GIT_BRANCH)
        if (stream in mechanical_streams) {
            build job: 'build', wait: false, parameters: [
              string(name: 'STREAM', value: stream)
            ]
        }
    } else {
        // cron or manual build: build all mechanical streams
        mechanical_streams.each{
            build job: 'build', wait: false, parameters: [
              string(name: 'STREAM', value: it),
              booleanParam(name: 'EARLY_ARCH_JOBS', value: false)
            ]
        }
    }
}
