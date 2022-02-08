def streams
node {
    checkout scm
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
         branches: streams.as_branches(streams.mechanical)
        ]
    )

    if (streams.triggered_by_push()) {
        stream = streams.from_branch(change.GIT_BRANCH)
        if (stream != "") {
            streams.build_stream(stream)
        }
    } else {
        // cron or manual build: build all mechanical streams
        streams.mechanical.each{ streams.build_stream(it) }
    }
}
