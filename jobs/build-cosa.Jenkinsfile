def pipeutils, streams, official
def gitref, commit, shortcommit
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
             defaultValue: "x86_64 ppc64le" + " " + streams.additional_arches.join(" "),
             trim: true),
      string(name: 'COREOS_ASSEMBLER_GIT_URL',
             description: 'Override the coreos-assembler git repo to use',
             defaultValue: "https://github.com/coreos/coreos-assembler.git",
             trim: true),
      string(name: 'COREOS_ASSEMBLER_GIT_REF',
             description: 'Override the coreos-assembler git ref to use',
             defaultValue: "",
             trim: true),
      string(name: 'CONTAINER_REGISTRY_REPO',
             description: 'Override the registry to push the container to',
             defaultValue: "quay.io/coreos-assembler/coreos-assembler",
             trim: true),
      string(name: 'CONTAINER_REGISTRY_STAGING_REPO',
             description: 'Override the staging registry where intermediate images go',
             defaultValue: "quay.io/coreos-assembler/coreos-assembler-staging",
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
            branches: [[name: 'origin/main']],
            userRemoteConfigs: [[url: 'https://github.com/coreos/coreos-assembler.git']],
            extensions: [[$class: 'CloneOption',
                          noTags: true,
                          reference: '',
                          shallow: true]]
        ]
    )

    // Handle here if we were triggered by a git webhook or triggered
    // manually by a human. If trigerred by a webhook we can pick up
    // the branch that was pushed to from $change. If not we need to
    // pick it up from params.COREOS_ASSEMBLER_GIT_REF specified by
    // the user (or "main" if not specified).
    gitref = params.COREOS_ASSEMBLER_GIT_REF
    if (pipeutils.triggered_by_push()) {
        gitref = change.GIT_BRANCH['origin/'.length()..-1]
    } else {
        if (gitref == "") {
            gitref = "main"
        }
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
    lock(resource: "build-cosa") {
    timeout(time: 60, unit: 'MINUTES') {
    cosaPod(image: params.COREOS_ASSEMBLER_IMAGE,
            memory: "256Mi", kvm: false) {

        currentBuild.description = "[${gitref}@${shortcommit}] Running"

        def force = params.FORCE ? "--force" : ""

        withCredentials([file(credentialsId: 'cosa-push-registry-secret', variable: 'REGISTRY_SECRET')]) {
            stage('Build COSA') {
                parallel basearches.collectEntries{arch -> [arch, {
                    pipeutils.withPodmanRemoteArchBuilder(arch: arch) {
                        shwrap("""
                        cosa remote-build-container \
                            --arch $arch --git-ref $commit ${force} \
                            --git-url ${params.COREOS_ASSEMBLER_GIT_URL} \
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
                cosa push-container-manifest --v2s2 \
                    --auth=\$REGISTRY_SECRET --tag ${gitref} \
                    --repo ${params.CONTAINER_REGISTRY_REPO} ${images}
                """)
                // Specifically for the `main` branch let's also update the `latest` tag
                // If there was a way to alias/tie these two together in the Quay UI
                // that would be preferable.
                if (gitref == "main") {
                    shwrap("""
                    skopeo copy --all --authfile \$REGISTRY_SECRET   \
                        docker://${params.CONTAINER_REGISTRY_REPO}:main \
                        docker://${params.CONTAINER_REGISTRY_REPO}:latest
                    """)
                }
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
        message = ":fcos: :trashfire: build-cosa <${env.BUILD_URL}|#${env.BUILD_NUMBER}> [${gitref}@${shortcommit}]"
        slackSend(color: 'danger', message: message)
    }
}
