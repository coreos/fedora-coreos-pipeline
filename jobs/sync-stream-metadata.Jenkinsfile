node {
    checkout scm
    // these are script global vars
    pipeutils = load("utils.groovy")
    pipecfg = pipeutils.load_pipecfg()
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

cosaPod() {
    git(url: 'https://github.com/coreos/fedora-coreos-streams',
        branch: 'main', credentialsId: 'github-coreosbot-token-username-password')
    withCredentials([file(credentialsId: 'aws-build-upload-config', variable: 'AWS_CONFIG_FILE')]) {
        def production_streams = pipeutils.streams_of_type(pipecfg, 'production')

        // NB: we don't use `aws s3 sync` here because it's timestamp-based and
        // so our fresh git clone will always seem newer and always get
        // uploaded. Instead, we manually copy in the S3 versions, check if
        // they're different from the checkout, and copy out the new versions
        // if so
        production_streams.each{stream ->
            for (subdir in ["streams", "updates"]) {
                shwrap("aws s3 cp s3://${pipecfg.s3.bucket}/${subdir}/${stream}.json ${subdir}/${stream}.json")
            }
            if (shwrapRc("git diff --exit-code") != 0) {
                shwrap("git reset --hard HEAD")
                for (subdir in ["streams", "updates"]) {
                    // NB: this metadata by definition is always public
                    shwrap("""
                        aws s3 cp --acl public-read --cache-control 'max-age=60' \
                            ${subdir}/${stream}.json s3://${pipecfg.s3.bucket}/${subdir}/${stream}.json
                    """)
                }
                pipeutils.tryWithMessagingCredentials() {
                    shwrap("""
                    /usr/lib/coreos-assembler/fedmsg-broadcast --fedmsg-conf=\${FEDORA_MESSAGING_CONF} \
                        stream.metadata.update --stream ${stream}
                    """)
                }
            }
            // Currently, we always re-upload release notes. We don't want to
            // falsely emit a stream.metadata.update message when only release
            // notes changed, and also the way change detection works above
            // doesn't mesh well with freshly regenerated data.
            // NB: this metadata by definition is always public
            shwrap("""
                python3 -c 'import sys, yaml, json; json.dump(yaml.safe_load(sys.stdin.read()), sys.stdout)' \
                    < release-notes/${stream}.yml > release-notes/${stream}.json
                aws s3 cp --acl public-read --cache-control 'max-age=60' \
                    release-notes/${stream}.json s3://${pipecfg.s3.bucket}/release-notes/${stream}.json
            """)
        }
    }
}
