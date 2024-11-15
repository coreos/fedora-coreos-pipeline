node {
    checkout scm
    // these are script global vars
    pipeutils = load("utils.groovy")
    pipecfg = pipeutils.load_pipecfg()
}

properties([
    pipelineTriggers(pipeutils.get_push_trigger()),
    buildDiscarder(logRotator(
        numToKeepStr: '100',
        artifactNumToKeepStr: '100'
    )),
    durabilityHint('PERFORMANCE_OPTIMIZED')
])

node {
    def development_streams = pipeutils.streams_of_type(pipecfg, 'development')

    change = checkout(
        [$class: 'GitSCM',
         userRemoteConfigs: [[url: pipecfg.source_config.url]],
         branches: pipeutils.streams_as_branches(development_streams)
        ]
    )

    stream = pipeutils.stream_from_branch(change.GIT_BRANCH)
    if (stream in development_streams) {
        build job: 'build', wait: false, parameters: [
          string(name: 'STREAM', value: stream),
          booleanParam(name: 'EARLY_ARCH_JOBS', value: false)
        ]
    }
}
