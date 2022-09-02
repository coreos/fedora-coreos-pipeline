def pipeutils, streams, official
def gitref, commit, shortcommit
def containername = 'fcos-buildroot'
node {
    checkout scm
    pipeutils = load("utils.groovy")
    streams = load("streams.groovy")
    official = pipeutils.isOfficial()
}

properties([
    pipelineTriggers([
        githubPush()
    ]),
    parameters([
      string(name: 'ARCHES',
             description: 'Space-separated list of target architectures',
             defaultValue: "x86_64",
             trim: true),
      string(name: 'CONFIG_GIT_URL',
             description: 'Override the fedora-coreos-config git repo to use',
             defaultValue: "https://github.com/coreos/fedora-coreos-config.git",
             trim: true),
      string(name: 'CONFIG_GIT_REF',
             description: 'Override the fedora-coreos-config git ref to use',
             defaultValue: "testing-devel",
             trim: true),
      string(name: 'CONTAINER_REGISTRY_REPO',
             description: 'Override the registry to push the container to',
             defaultValue: "quay.io/coreos-assembler/${containername}",
             trim: true),
      string(name: 'CONTAINER_REGISTRY_STAGING_REPO',
             description: 'Override the staging registry where intermediate images go',
             defaultValue: "quay.io/coreos-assembler/staging",
             trim: true),
      string(name: 'COREOS_ASSEMBLER_IMAGE',
             description: 'Override the coreos-assembler image to use',
             defaultValue: "coreos-assembler:main",
             trim: true),
      booleanParam(name: 'FORCE',
                   defaultValue: false,
                   description: 'Whether to force a rebuild'),
    ]),
    buildDiscarder(logRotator(
        numToKeepStr: '100',
        artifactNumToKeepStr: '100'
    )),
    durabilityHint('PERFORMANCE_OPTIMIZED')
])

node {
    change = checkout(
        changelog: true,
        poll: false,
        scm: [
            $class: 'GitSCM',
            branches: [[name: 'origin/testing-devel']],
            userRemoteConfigs: [[url: params.CONFIG_GIT_URL]],
            extensions: [[$class: 'CloneOption',
                          noTags: true,
                          reference: '',
                          shallow: true]]
        ]
    )

    // Handle here if we were triggered by a git webhook or triggered
    // manually by a human. If trigerred by a webhook we can pick up
    // the branch that was pushed to from $change. If not we need to
    // pick it up from params.CONFIG_GIT_REF specified by
    // the user (or "main" if not specified).
    gitref = params.CONFIG_GIT_REF
    if (pipeutils.triggered_by_push()) {
        gitref = change.GIT_BRANCH['origin/'.length()..-1]
    } else {
        shwrap("git fetch --depth=1 origin ${gitref} && git checkout FETCH_HEAD")
    }
    def output = shwrapCapture("git rev-parse HEAD")
    commit = output.substring(0,40)
    shortcommit = commit.substring(0,7)
}

currentBuild.description = "[${gitref}@${shortcommit}] Waiting"

// Get the list of requested architectures to build for
def basearches = params.ARCHES.split() as Set

try {
    lock(resource: "build-${containername}") {
    timeout(time: 60, unit: 'MINUTES') {
    cosaPod(image: params.COREOS_ASSEMBLER_IMAGE,
            memory: "256Mi", kvm: false) {

        currentBuild.description = "[${gitref}@${shortcommit}] Running"

        // By default we will allow re-using cache layers for one day.
        // This is mostly so we can prevent re-downloading the RPMS
        // and repo metadata and over again in a given day for successive
        // builds.
        def cacheTTL = "24h"
        def force = ""
        if (params.FORCE) {
            force = '--force'
            // Also set cacheTTL to 0.1s to allow users an escape hatch
            // to force no cache layer usage.
            cacheTTL = "0.1s"
        }

        withCredentials([file(credentialsId: 'cosa-push-registry-secret', variable: 'REGISTRY_SECRET')]) {
            stage('Build Container(s)') {
                parallel basearches.collectEntries{arch -> [arch, {
                    pipeutils.withPodmanRemoteArchBuilder(arch: arch) {
                        shwrap("""
                        cosa remote-build-container \
                            --arch $arch --cache-ttl ${cacheTTL} \
                            --git-ref $commit ${force} \
                            --git-url ${params.CONFIG_GIT_URL} \
                            --git-sub-dir "ci/buildroot" \
                            --repo ${params.CONTAINER_REGISTRY_STAGING_REPO} \
                            --push-to-registry --auth=\$REGISTRY_SECRET
                        """)
                    }
                }]}
            }

            stage('Push Manifest') {
                def images = ""
                for (architecture in basearches) {
                    def arch = architecture
                    images += " --image=docker://${params.CONTAINER_REGISTRY_STAGING_REPO}:${arch}-${shortcommit}"
                }
                shwrap("""
                cosa push-container-manifest \
                    --auth=\$REGISTRY_SECRET --tag ${gitref} \
                    --repo ${params.CONTAINER_REGISTRY_REPO} ${images}
                """)
            }

            stage('Delete Intermediate Tags') {
                parallel basearches.collectEntries{arch -> [arch, {
                    shwrap("""
                    skopeo delete --authfile=\$REGISTRY_SECRET \
                        docker://${params.CONTAINER_REGISTRY_STAGING_REPO}:${arch}-${shortcommit}
                    """)
                }]}
            }
        }
        currentBuild.result = 'SUCCESS'
    }
}}} catch (e) {
    currentBuild.result = 'FAILURE'
    throw e
} finally {
    if (currentBuild.result == 'SUCCESS') {
        currentBuild.description = "[${gitref}@${shortcommit}] ⚡"
    } else {
        currentBuild.description = "[${gitref}@${shortcommit}] ❌"
    }
    if (official && currentBuild.result != 'SUCCESS') {
        message = ":fcos: :trashfire: build-${containername} <${env.BUILD_URL}|#${env.BUILD_NUMBER}> [${gitref}@${shortcommit}]"
        slackSend(color: 'danger', message: message)
    }
}
