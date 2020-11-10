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

        // NB: we don't use `aws s3 sync` here because it's timestamp-based and
        // so our fresh git clone will always seem newer and always get
        // uploaded. Instead, we manually copy in the S3 versions, check if
        // they're different from the checkout, and copy out the new versions
        // if so
        streams.production.each{stream ->
            for (subdir in ["streams", "updates"]) {
                shwrap("aws s3 cp s3://fcos-builds/${subdir}/${stream}.json ${subdir}/${stream}.json")
            }
            if (shwrapRc("git diff --exit-code") != 0) {
                shwrap("git reset --hard HEAD")
                for (subdir in ["streams", "updates"]) {
                    shwrap("aws s3 cp ${subdir}/${stream}.json s3://fcos-builds/${subdir}/${stream}.json")
                }
                utils.shwrap("""
                /var/tmp/fcos-releng/scripts/broadcast-fedmsg.py --fedmsg-conf=\${FEDORA_MESSAGING_CFG}/fedmsg.toml \
                    stream.metadata.update --stream ${stream}
                """)
            }
        }
    }
}
