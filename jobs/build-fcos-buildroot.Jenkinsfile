def gitref, commit, shortcommit
def containername = 'fcos-buildroot'
node {
    checkout scm
    // these are script global vars
    pipeutils = load("utils.groovy")
    pipecfg = pipeutils.load_pipecfg()
}

properties([
    pipelineTriggers([
        // run every 3 days to ensure regular updates
        cron("H H */3 * *"),
        // and trigger on webhooks when ci/buildroot/ files have changed
        [$class: 'GenericTrigger',
         genericVariables: [
          [
           key: 'CONFIG_GIT_REF',
           value: '$.ref',
           expressionType: 'JSONPath',
           regexpFilter: 'refs/heads/', //Optional, defaults to empty string
           defaultValue: ''  //Optional, defaults to empty string
          ],
          [
           key: 'changed_files',
           value: "\$.commits[*].['modified','added','removed'][*]",
           expressionType: 'JSONPath',
           regexpFilter: '', //Optional, defaults to empty string
           defaultValue: ''  //Optional, defaults to empty string
          ]
         ],
         causeString: 'Triggered on $ref',
         token: 'build-fcos-buildroot',
         tokenCredentialId: '',
         printContributedVariables: true,
         printPostContent: true,
         silentResponse: false,
         regexpFilterText: '$CONFIG_GIT_REF $changed_files',
         regexpFilterExpression: 'testing-devel .*ci/buildroot/.*'
        ]
    ]),
    parameters([
      string(name: 'ARCHES',
             description: 'Space-separated list of target architectures',
             defaultValue: "x86_64",
             trim: true),
      string(name: 'CONFIG_GIT_URL',
             description: 'Override the src/config git repo to use',
             defaultValue: pipecfg.source_config.url,
             trim: true),
      string(name: 'CONFIG_GIT_REF',
             description: 'Override the src/config git ref to use',
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
            branches: [[name: "origin/${params.CONFIG_GIT_REF}"]],
            userRemoteConfigs: [[url: params.CONFIG_GIT_URL]],
            extensions: [[$class: 'CloneOption',
                          noTags: true,
                          reference: '',
                          shallow: true]]
        ]
    )

    gitref = params.CONFIG_GIT_REF
    def output = shwrapCapture("git rev-parse HEAD")
    commit = output.substring(0,40)
    shortcommit = commit.substring(0,7)
}

currentBuild.description = "[${gitref}@${shortcommit}] Waiting"

// Get the list of requested architectures to build for
def basearches = params.ARCHES.split() as Set

lock(resource: "build-${containername}") {
    cosaPod(image: params.COREOS_ASSEMBLER_IMAGE,
            memory: "256Mi", kvm: false,
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
                export STORAGE_DRIVER=vfs # https://github.com/coreos/fedora-coreos-pipeline/issues/723#issuecomment-1297668507
                cosa push-container-manifest \
                    --auth=\$REGISTRY_SECRET --tag ${gitref} \
                    --repo ${params.CONTAINER_REGISTRY_REPO} ${images}
                """)
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
            message = "build-${containername} <${env.BUILD_URL}|#${env.BUILD_NUMBER}> [${gitref}@${shortcommit}]"
            pipeutils.trySlackSend(color: 'danger', message: message)
        }
    }
}}} // cosaPod, timeout, and lock finish here
