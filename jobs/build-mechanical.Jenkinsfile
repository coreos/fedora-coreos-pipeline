def streams, pipeutils
node {
    checkout scm
    pipeutils = load("utils.groovy")
    streams = load("streams.groovy")
}

properties([
    pipelineTriggers([
        // run every 24h only for now
        cron("H H * * *")
    ]),
    durabilityHint('PERFORMANCE_OPTIMIZED')
])

node {
    change = checkout(
        [$class: 'GitSCM',
         userRemoteConfigs: [
            [url: 'https://github.com/coreos/fedora-coreos-config']
         ],
         branches: pipeutils.streams_as_branches(streams.mechanical)
        ]
    )

    if (pipeutils.triggered_by_push()) {
        stream = pipeutils.stream_from_branch(change.GIT_BRANCH)
        if (stream in streams.mechanical) {
            build job: 'build', wait: false, parameters: [
              string(name: 'STREAM', value: stream)
            ]
        }
    } else {
        // cron or manual build: build all mechanical streams
        streams.mechanical.each{
            build job: 'build', wait: false, parameters: [
              string(name: 'STREAM', value: it)
            ]
        }
    }
}
