def pipecfg, pipeutils
node {
    checkout scm
    pipeutils = load("utils.groovy")
    pipecfg = pipeutils.load_pipecfg()
}

properties([
    pipelineTriggers(pipeutils.get_push_trigger()),
    durabilityHint('PERFORMANCE_OPTIMIZED')
])

node {
    def development_streams = pipeutils.streams_of_type(pipecfg, 'development')

    change = checkout(
        [$class: 'GitSCM',
         userRemoteConfigs: [
            [url: 'https://github.com/coreos/fedora-coreos-config']
         ],
         branches: pipeutils.streams_as_branches(development_streams)
        ]
    )

    stream = pipeutils.stream_from_branch(change.GIT_BRANCH)
    if (stream in streams.development) {
        build job: 'build', wait: false, parameters: [
          string(name: 'STREAM', value: stream)
        ]
    }
}
