@Library('github.com/coreos/coreos-ci-lib@master') _

properties([
    pipelineTriggers([
        githubPush(),
        // also run every 15 mins as a fallback in case webhooks are down
        pollSCM('H/15 * * * *')
    ])
])

cosaPod {
    git(url: 'https://github.com/coreos/fedora-coreos-streams', credentialsId: 'github-coreosbot-token')
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', credentialsId: 'aws-fcos-builds-bot']]) {
        shwrap("""
            aws s3 sync --acl public-read --cache-control 'max-age=60' \
                --exclude '*' --include 'streams/*' --include 'updates/*' \
                    ./ s3://fcos-builds
        """)
    }
}
