properties([
    pipelineTriggers([
        // run every 24h at 04:00 UTC
        cron("0 4 * * *")
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
    def mechanical_streams = pipeutils.streams_of_type(pipecfg, 'mechanical')
    streams_to_bump = development_streams + mechanical_streams

    parallel streams_to_bump.collectEntries { stream -> [stream, {
        build job: 'bump-lockfile', wait: false, parameters: [
            string(name: 'STREAM', value: stream)
        ]
    }] }
}
