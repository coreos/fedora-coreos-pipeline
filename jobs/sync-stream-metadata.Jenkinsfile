@Library('github.com/coreos/coreos-ci-lib') _

def streams
node {
    checkout scm
    streams = load("streams.groovy")
}

properties([
    pipelineTriggers([
        githubPush(),
        // also run every 15 mins as a fallback in case webhooks are down
        pollSCM('H/15 * * * *')
    ])
])

cosaPod(configMaps: ["fedora-messaging-cfg"], secrets: ["fedora-messaging-coreos-key"]) {
    git(url: 'https://github.com/coreos/fedora-coreos-streams', credentialsId: 'github-coreosbot-token')
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', credentialsId: 'aws-fcos-builds-bot']]) {
        // XXX: eventually we want this as part of the pod or built into the image we use
        shwrap("git clone --depth=1 https://github.com/coreos/fedora-coreos-releng-automation /var/tmp/fcos-releng")

        streams.production.each{stream ->
            def changed = false
            for (subdir in ["streams", "updates"]) {
                def out = shwrapCapture("""aws s3 sync --acl public-read --cache-control 'max-age=60' \
                        --exclude '*' --include '${stream}.json' ${subdir} s3://fcos-builds/${subdir}""").trim()
                println(out)
                changed = changed || (out != "")
            }
            if (changed) {
                utils.shwrap("""
                /var/tmp/fcos-releng/scripts/broadcast-fedmsg.py --fedmsg-conf=\${FEDORA_MESSAGING_CFG}/fedmsg.toml \
                    stream.metadata.update --stream ${stream}
                """)
            }
        }
    }
}
