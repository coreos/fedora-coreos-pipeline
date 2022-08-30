properties([
    pipelineTriggers([
        // we don't need to bump lockfiles any more often than daily
        cron("H H * * *")
    ]),
    buildDiscarder(logRotator(
        numToKeepStr: '100',
        artifactNumToKeepStr: '100'
    )),
    durabilityHint('PERFORMANCE_OPTIMIZED')
])

node {
    checkout scm
    def pipeutils = load("utils.groovy")
    def pipecfg = pipeutils.load_pipecfg()
    def development_streams = pipeutils.streams_of_type(pipecfg, 'development')

    parallel development_streams.collectEntries { stream -> [stream, {
        build job: 'bump-lockfile', wait: false, parameters: [
            string(name: 'STREAM', value: stream)
        ]
    }] }
}
