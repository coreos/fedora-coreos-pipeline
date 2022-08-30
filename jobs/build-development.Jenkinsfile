def streams, pipeutils
node {
    checkout scm
    pipeutils = load("utils.groovy")
    streams = load("streams.groovy")
}

properties([
    pipelineTriggers(pipeutils.get_push_trigger()),
    durabilityHint('PERFORMANCE_OPTIMIZED')
])

node {
    change = checkout(
        [$class: 'GitSCM',
         userRemoteConfigs: [
            [url: 'https://github.com/coreos/fedora-coreos-config']
         ],
         branches: pipeutils.streams_as_branches(streams.development)
        ]
    )

    stream = pipeutils.stream_from_branch(change.GIT_BRANCH)
    if (stream in streams.development) {
        build job: 'build', wait: false, parameters: [
          string(name: 'STREAM', value: stream)
        ]
    }
}
