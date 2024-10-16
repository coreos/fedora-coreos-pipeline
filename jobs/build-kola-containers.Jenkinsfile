def gitref, commit, shortcommit, contexts, changeset
node {
    checkout scm
    // these are script global vars
    pipeutils = load("utils.groovy")
}

properties([
    pipelineTriggers([
        // run weekly to ensure regular updates
        cron("@weekly"),
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
         token: 'build-kola-test-containers',
         tokenCredentialId: '',
         printContributedVariables: true,
         printPostContent: true,
         silentResponse: false,
         regexpFilterText: '$COREOS_ASSEMBLER_GIT_REF',
         regexpFilterExpression: 'main'
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
      string(name: 'CONTAINER_REGISTRY_ORG',
             description: 'Override the registry org to push the containers to',
             defaultValue: "quay.io/coreos-assembler",
             trim: true),
      string(name: 'CONTAINER_REGISTRY_STAGING_REPO',
             description: 'Override the staging registry where intermediate images go',
             defaultValue: "quay.io/coreos-assembler/staging",
             trim: true),
      string(name: 'PATH_TO_CONTEXTS',
             description: """Override the path to the contexts directories to use as build contexts.
             Each directory should contain a Containerfile.
             The image will be named after the directory name.""",
             defaultValue: "tests/containers/",
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
                          shallow: false]]
        ]
    )

    gitref = params.COREOS_ASSEMBLER_GIT_REF
    def output = shwrapCapture("git rev-parse HEAD")
    commit = output.substring(0,40)
    shortcommit = commit.substring(0,7)

    // gather the context folders list
    def path = params.PATH_TO_CONTEXTS
    contexts = shwrapCapture("""
        cd ${path}
        find . -maxdepth 1 -mindepth 1 -type d -exec basename {} \\;
    """).trim().split("\n")
}

currentBuild.description = "[${gitref}@${shortcommit}] Waiting"

// Get the list of requested architectures to build for
def basearches = params.ARCHES.split() as Set
// and  the list of images to build
def imageNames = contexts as Set

lock(resource: "build-kola-containers") {
    cosaPod(image: params.COREOS_ASSEMBLER_IMAGE,
            memory: "512Mi", kvm: false,
            serviceAccount: "jenkins") {
    timeout(time: 60, unit: 'MINUTES') {
    try {

        if ( !params.FORCE ) {
            // get the git commit ref for the last built container
            def previous_ref = shwrapCapture("""
                skopeo inspect --no-creds docker://${params.CONTAINER_REGISTRY_ORG}/${contexts[0]}:latest \
                | jq -r '.Labels.org.opencontainers.image.revision'
            """)

            // Check for changes in tests/containers/* since the last build
            // If none, no need to run this
            def path = params.PATH_TO_CONTEXTS
            changeset = shwrapRc("git diff --quiet ${previous_ref} -- ${path}")

        } else {
            changeset = 1
        }

        if ( changeset == 0 ) {
            currentBuild.result = 'SUCCESS'
            currentBuild.description = "[${gitref}@${shortcommit}] 💤 (no change)."
            return
        } else if ( changeset != 1 ) {
            currentBuild.result = 'FAILURE'
            currentBuild.description = "[${gitref}@${shortcommit}] ❌ Cannot determine changes. git diff return code ${changeset}."
            message = "build-kola-test-containers #${env.BUILD_NUMBER} <${env.BUILD_URL}|:jenkins:> <${env.RUN_DISPLAY_URL}|:ocean:> [${gitref}@${shortcommit}]"
            pipeutils.trySlackSend(message: message)
            return
        }

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
            stage('Build Containers') {
                parallel basearches.collectEntries{arch -> [arch, {
                    for (imageName in imageNames) {
                        def dirname = "${params.PATH_TO_CONTEXTS}/${imageName}"
                        pipeutils.withPodmanRemoteArchBuilder(arch: arch) {
                            // Forcing the tag to include the image name
                            // prevents racing between arches and overwrite the images
                            shwrap("""
                                cosa remote-build-container \
                                    --git-sub-dir ${dirname}\
                                    --arch ${arch} --cache-ttl ${cacheTTL} \
                                    --git-ref ${commit} ${force} \
                                    --git-url ${params.COREOS_ASSEMBLER_GIT_URL} \
                                    --tag ${imageName}-${arch}-${shortcommit} \
                                    --repo ${params.CONTAINER_REGISTRY_STAGING_REPO} \
                                    --push-to-registry --auth=\$REGISTRY_SECRET
                            """)
                        }
                    }
                }]}
            }

            stage('Push Manifests') {
                for (imageName in imageNames) {
                    def images = ""
                    for (arch in basearches) {
                        images += " --image=docker://${params.CONTAINER_REGISTRY_STAGING_REPO}:${imageName}-${arch}-${shortcommit}"
                    }

                    // arbitrarily selecting the x86_64 builder; we don't run this
                    // locally because podman wants user namespacing (yes, even just
                    // to push a manifest...)
                    pipeutils.withPodmanRemoteArchBuilder(arch: "x86_64") {
                        shwrap("""
                            cosa push-container-manifest --v2s2 \
                                --auth=\$REGISTRY_SECRET --tag latest \
                                --repo ${params.CONTAINER_REGISTRY_ORG}/${imageName} ${images}
                        """)
                    }
                }
            }


            stage('Delete Intermediate Tags') {
                for (imageName in imageNames) {
                    for (arch in basearches) {
                        shwrap("""
                        export STORAGE_DRIVER=vfs # https://github.com/coreos/fedora-coreos-pipeline/issues/723#issuecomment-1297668507
                        skopeo delete --authfile=\$REGISTRY_SECRET \
                            docker://${params.CONTAINER_REGISTRY_STAGING_REPO}:${imageName}-${arch}-${shortcommit}
                        """)
                    }
                }
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
            message = "build-kola-test-containers #${env.BUILD_NUMBER} <${env.BUILD_URL}|:jenkins:> <${env.RUN_DISPLAY_URL}|:ocean:> [${gitref}@${shortcommit}]"
            pipeutils.trySlackSend(message: message)
        }
    }
}}} // cosaPod, timeout, and lock finish here

