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
            def cmd = """
                aws s3 sync --acl public-read --cache-control 'max-age=60' \
                    --exclude '*' --include 'streams/${stream}.json' --include 'updates/${stream}.json' \
                        ./ s3://fcos-builds
            """

            // trim so that when we append --dryrun below, it's on the same line
            cmd = cmd.trim()

            // Do a dry run first to see if there's any work that actually needs to
            // be done. `aws s3 sync` doesn't print anything if everything is
            // synced up.
            def dry_run = shwrapCapture("${cmd} --dryrun").trim()
            if (dry_run != "") {
                shwrap("${cmd}")
                utils.shwrap("""
                /var/tmp/fcos-releng/scripts/broadcast-fedmsg.py --fedmsg-conf=\${FEDORA_MESSAGING_CFG}/fedmsg.toml \
                    stream.metadata.update --stream ${stream}
                """)
            }
        }
    }
}
