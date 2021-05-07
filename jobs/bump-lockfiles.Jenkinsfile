@Library('github.com/coreos/coreos-ci-lib@main') _

properties([
    pipelineTriggers([
        // we don't need to bump lockfiles any more often than daily
        cron("H H * * *")
    ])
])

node {
    checkout scm
    def streams = load("streams.groovy")

    parallel streams.development.collectEntries { stream -> [stream, {
        build job: 'bump-lockfile', wait: false, parameters: [
            string(name: 'STREAM', value: stream)
        ]
    }] }
}
