def streams
node {
    checkout scm
    streams = load("streams.groovy")
}

properties([
    pipelineTriggers(streams.get_push_trigger()),
    durabilityHint('PERFORMANCE_OPTIMIZED')
])

node {
    change = checkout(
        [$class: 'GitSCM',
         userRemoteConfigs: [
            [url: 'https://github.com/coreos/fedora-coreos-config']
         ],
         branches: streams.as_branches(streams.development)
        ]
    )

    stream = streams.from_branch(change.GIT_BRANCH)
    if (stream != "") {
        streams.build_stream(stream)
    }
}
