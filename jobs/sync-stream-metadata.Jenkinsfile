def pipecfg, pipeutils
node {
    checkout scm
    pipecfg = readYaml file: "config.yaml"
    pipeutils = load("utils.groovy")
}

properties([
    pipelineTriggers([
        githubPush(),
        // also run every 15 mins as a fallback in case webhooks are down
        pollSCM('H/15 * * * *')
    ]),
    buildDiscarder(logRotator(
        numToKeepStr: '100',
        artifactNumToKeepStr: '100'
    )),
    durabilityHint('PERFORMANCE_OPTIMIZED')
])

cosaPod(configMaps: ["fedora-messaging-cfg"], secrets: ["fedora-messaging-coreos-key"]) {
    git(url: 'https://github.com/coreos/fedora-coreos-streams', branch: 'main', credentialsId: 'github-coreosbot-token')
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', credentialsId: 'aws-fcos-builds-bot']]) {
        // XXX: eventually we want this as part of the pod or built into the image we use
        shwrap("git clone --depth=1 https://github.com/coreos/fedora-coreos-releng-automation /var/tmp/fcos-releng")

        def production_streams = pipeutils.streams_of_type(pipecfg, 'production')

        // NB: we don't use `aws s3 sync` here because it's timestamp-based and
        // so our fresh git clone will always seem newer and always get
        // uploaded. Instead, we manually copy in the S3 versions, check if
        // they're different from the checkout, and copy out the new versions
        // if so
        production_streams.each{stream ->
            for (subdir in ["streams", "updates"]) {
                shwrap("aws s3 cp s3://fcos-builds/${subdir}/${stream}.json ${subdir}/${stream}.json")
            }
            if (shwrapRc("git diff --exit-code") != 0) {
                shwrap("git reset --hard HEAD")
                for (subdir in ["streams", "updates"]) {
                    shwrap("""
                        aws s3 cp --acl public-read --cache-control 'max-age=60' \
                            ${subdir}/${stream}.json s3://fcos-builds/${subdir}/${stream}.json
                    """)
                }
                shwrap("""
                /var/tmp/fcos-releng/scripts/broadcast-fedmsg.py --fedmsg-conf=\${FEDORA_MESSAGING_CFG}/fedmsg.toml \
                    stream.metadata.update --stream ${stream}
                """)
            }
            // Currently, we always re-upload release notes. We don't want to
            // falsely emit a stream.metadata.update message when only release
            // notes changed, and also the way change detection works above
            // doesn't mesh well with freshly regenerated data.
            shwrap("""
                python3 -c 'import sys, yaml, json; json.dump(yaml.safe_load(sys.stdin.read()), sys.stdout)' \
                    < release-notes/${stream}.yml > release-notes/${stream}.json
                aws s3 cp --acl public-read --cache-control 'max-age=60' \
                    release-notes/${stream}.json s3://fcos-builds/release-notes/${stream}.json
            """)
        }
    }
}
