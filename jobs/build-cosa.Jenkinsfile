def gitref, commit, shortcommit
def containername = 'coreos-assembler'
node {
    checkout scm
    // these are script global vars
    pipeutils = load("utils.groovy")
}

properties([
    pipelineTriggers([
        [$class: 'GenericTrigger',
         genericVariables: [
          [
           key: 'COREOS_ASSEMBLER_GIT_REF',
           value: '$.ref',
           expressionType: 'JSONPath',
           regexpFilter: 'refs/heads/', //Optional, defaults to empty string
           defaultValue: ''  //Optional, defaults to empty string
          ]
         ],
         causeString: 'Triggered on $ref',
         token: 'build-cosa',
         tokenCredentialId: '',
         printContributedVariables: true,
         printPostContent: true,
         silentResponse: false,
         regexpFilterText: '$COREOS_ASSEMBLER_GIT_REF',
         regexpFilterExpression: 'main|rhcos-.*'
        ]
    ]),
    parameters([
      string(name: 'ARCHES',
             description: 'Space-separated list of target architectures',
             defaultValue: "x86_64" + " " + pipeutils.get_supported_additional_arches().join(" "),
             trim: true),
      string(name: 'COREOS_ASSEMBLER_GIT_URL',
             description: 'Override the coreos-assembler git repo to use',
             defaultValue: "https://github.com/coreos/coreos-assembler.git",
             trim: true),
      string(name: 'COREOS_ASSEMBLER_GIT_REF',
             description: 'Override the coreos-assembler git ref to use',
             defaultValue: "main",
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
             defaultValue: "quay.io/coreos-assembler/coreos-assembler:main",
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
            branches: [[name: "origin/${params.COREOS_ASSEMBLER_GIT_REF}"]],
            userRemoteConfigs: [[url: params.COREOS_ASSEMBLER_GIT_URL]],
            extensions: [[$class: 'CloneOption',
                          noTags: true,
                          reference: '',
                          shallow: true]]
        ]
    )

    gitref = params.COREOS_ASSEMBLER_GIT_REF
    def output = shwrapCapture("git rev-parse HEAD")
    commit = output.substring(0,40)
    shortcommit = commit.substring(0,7)
}

currentBuild.description = "[${gitref}@${shortcommit}] Waiting"

// Get the list of requested architectures to build for
def basearches = params.ARCHES.split() as Set

lock(resource: "build-${containername}") {
    cosaPod(image: params.COREOS_ASSEMBLER_IMAGE,
            memory: "512Mi", kvm: false,
            serviceAccount: "jenkins") {
    timeout(time: 60, unit: 'MINUTES') {
    try {

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
            stage('Build COSA') {
                parallel basearches.collectEntries{arch -> [arch, {
                    pipeutils.withPodmanRemoteArchBuilder(arch: arch) {
                        shwrap("""
                        cosa remote-build-container \
                            --arch $arch --cache-ttl ${cacheTTL} \
                            --git-ref $commit ${force} \
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
                export STORAGE_DRIVER=vfs # https://github.com/coreos/fedora-coreos-pipeline/issues/723#issuecomment-1297668507
                cosa push-container-manifest --v2s2 \
                    --auth=\$REGISTRY_SECRET --tag ${gitref} \
                    --repo ${params.CONTAINER_REGISTRY_REPO} ${images}
                """)
                // Specifically for the `main` branch let's also update the `latest` tag
                // If there was a way to alias/tie these two together in the Quay UI
                // that would be preferable.
                if (gitref == "main") {
                    shwrap("""
                    export STORAGE_DRIVER=vfs # https://github.com/coreos/fedora-coreos-pipeline/issues/723#issuecomment-1297668507
                    skopeo copy --all --authfile \$REGISTRY_SECRET   \
                        docker://${params.CONTAINER_REGISTRY_REPO}:main \
                        docker://${params.CONTAINER_REGISTRY_REPO}:latest
                    """)
                }
            }

            stage('Delete Intermediate Tags') {
                parallel basearches.collectEntries{arch -> [arch, {
                    shwrap("""
                    export STORAGE_DRIVER=vfs # https://github.com/coreos/fedora-coreos-pipeline/issues/723#issuecomment-1297668507
                    skopeo delete --authfile=\$REGISTRY_SECRET \
                        docker://${params.CONTAINER_REGISTRY_STAGING_REPO}:${arch}-${shortcommit}
                    """)
                }]}
            }
        }
        currentBuild.result = 'SUCCESS'

    } catch (e) {
        currentBuild.result = 'FAILURE'
        throw e
    } finally {
        if (currentBuild.result == 'SUCCESS') {
            currentBuild.description = "[${gitref}@${shortcommit}] ⚡"
        } else {
            currentBuild.description = "[${gitref}@${shortcommit}] ❌"
        }
        if (currentBuild.result != 'SUCCESS') {
            message = "build-cosa <${env.BUILD_URL}|#${env.BUILD_NUMBER}> [${gitref}@${shortcommit}]"
            pipeutils.trySlackSend(message: message)
        }
    }
}}} // cosaPod, timeout, and lock finish here

