def gitref, commit, shortcommit

node {
    checkout scm
    // these are script global vars
    pipeutils = load("utils.groovy")
    pipecfg = pipeutils.load_pipecfg()
}

properties([
    pipelineTriggers([cron('H H * * *')]),
    parameters([
      choice(name: 'RELEASE',
             choices: pipeutils.get_streams_choices(pipecfg, true),
             description: 'CoreOS stream to build'),
      string(name: 'FROM',
             description: 'Image FROM',
             defaultValue: '',
             trim: true),
      string(name: 'ARCHES',
             description: 'Space-separated list of target architectures',
             defaultValue: "x86_64 aarch64 ppc64le s390x",
             trim: true),
      string(name: 'COREOS_ASSEMBLER_IMAGE',
             description: 'Override the coreos-assembler image to use',
             defaultValue: "quay.io/coreos-assembler/coreos-assembler:latest",
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

// Note the supermin VM just uses 2G. The really hungry part is xz, which
// without lots of memory takes lots of time. For now we just hardcode these
// here; we can look into making them configurable through the template if
// developers really need to tweak them.
// XXX bump an extra 2G (to 10.5) because of an error we are seeing in
// testiso: https://github.com/coreos/fedora-coreos-tracker/issues/1339
def cosa_memory_request_mb = 4.5 * 1024 as Integer

// Now that we've established the memory constraint based on xz above, derive
// kola parallelism from that. We leave 512M for overhead and VMs are at most
// 1.5G each (in general; root reprovisioning tests require 4G which is partly
// why we run them separately).
// XXX: https://github.com/coreos/coreos-assembler/issues/3118 will make this
// cleaner
def ncpus = ((cosa_memory_request_mb - 512) / 1536) as Integer

// runtime parameter always wins
def cosa_img = params.COREOS_ASSEMBLER_IMAGE
cosa_img = cosa_img ?: pipeutils.get_cosa_img(pipecfg, params.RELEASE)

def timeout_mins = 300

def stream_info = pipecfg.node_builds.ocp_release[params.RELEASE]
def src_config_ref = stream_info.source_config.ref
def src_config_url = stream_info.source_config.url

node {

    change = checkout(
        changelog: true,
        poll: false,
        scm: [
            $class: 'GitSCM',
            branches: [[name: "origin/${src_config_ref}"]],
            userRemoteConfigs: [[url: src_config_url]],
            extensions: [[$class: 'CloneOption',
                          noTags: true,
                          reference: '',
                          shallow: true]]
        ]
    )

    def output = shwrapCapture("git rev-parse HEAD")
    commit = output.substring(0,40)
    shortcommit = commit.substring(0,7)
}

lock(resource: "build-node-image") {
    cosaPod(cpu: "${ncpus}",
            memory: "${cosa_memory_request_mb}Mi",
            image: params.COREOS_ASSEMBLER_IMAGE,
            serviceAccount: "jenkins") {
    timeout(time: 60, unit: 'MINUTES') {
    try {

        currentBuild.description = "[${src_config_ref}@${shortcommit}] Running"
        // Get the list of requested architectures to build for
        def arches = params.ARCHES.split() as Set
        def archinfo = arches.collectEntries{[it, [:]]}
        for (arch in archinfo.keySet()) {
            // initialize some data
            archinfo[arch]['session'] = ""
        }

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

        def container_registry_staging_repo = pipecfg.node_builds.registries.staging
        def container_registry_repo = pipecfg.node_builds.registries.prod

        // add any additional root CA cert before we do anything that fetches
        pipeutils.addOptionalRootCA()

        stage('Init') {
            def yumrepos = stream_info.yumrepos ? "--yumrepos ${stream_info.yumrepos}" : ""
            def variant = stream_info.variant ? "--variant ${stream_info.variant}" : ""
            shwrap("""
            cosa init --force --branch ${src_config_ref} --commit=${commit} ${yumrepos} ${variant} ${src_config_url}
            """)
        }

        stage('Build Layered Image') {
            withCredentials([file(credentialsId: 'oscontainer-push-registry-secret', variable: 'REGISTRY_SECRET')]) {
                def src_config_from = params.FROM ?: stream_info.from
                parallel arches.collectEntries{arch -> [arch, {
                    pipeutils.withPodmanRemoteArchBuilder(arch: arch) {
                        shwrap("""
                            cosa remote-build-container --arch $arch \
                                --git-ref $commit ${force} \
                                --git-url ${src_config_url} \
                                --repo ${container_registry_staging_repo} \
                                --push-to-registry --auth=\${REGISTRY_SECRET} \
                                --secret id=yumrepos,src=\$(pwd)/src/yumrepos/${stream_info.variant}.repo \
                                --mount-host-ca-certs \
                                --security-opt label=disable \
                                --from ${src_config_from} \
                                --tag ${params.RELEASE}-${arch}-${shortcommit}
                        """)
                    }
                }]}
            }
        }
        withCredentials([file(credentialsId: 'oscontainer-push-registry-secret', variable: 'REGISTRY_SECRET')]) {
            stage("Push Manifest") {
                def manifest = "${container_registry_staging_repo}:node-image-${params.RELEASE}-${shortcommit}"
                def cmds = "/usr/bin/buildah manifest create ${manifest}"
                parallel archinfo.keySet().collectEntries{arch -> [arch, {
                    def image = "docker://${container_registry_staging_repo}:${params.RELEASE}-${arch}-${shortcommit}"
                    cmds += " && /usr/bin/buildah manifest add ${manifest} ${image}"
                }]}
                //  `cosa supermin-run` only mounts the current directory, so we
                // need to temporarily put the secret here. We'll delete it right after.
                shwrap("""cp ${REGISTRY_SECRET} push-secret""")

                // We need to run all buildah commands in the same superminvm to not lose
                // the manifest creation/additions
                shwrap("""cosa supermin-run bash -c \
                    "${cmds} && \
                    /usr/bin/buildah manifest push --authfile ./push-secret --all ${manifest} \
                    docker://${manifest}"
                """)
                shwrap("""rm -f  push-secret""")
            }
        }
        withCredentials([file(credentialsId: 'oscontainer-push-registry-secret', variable: 'REGISTRY_SECRET')]) {
           stage("Release Manifest") {
                // Release the manifest and container images
                shwrap("""
                    skopeo copy --all --authfile \$REGISTRY_SECRET   \
                    docker://${container_registry_staging_repo}:node-image-${params.RELEASE}-${shortcommit} \
                    docker://${container_registry_repo}}:${params.RELEASE}-node
                """)
            }
        }
        withCredentials([file(credentialsId: 'oscontainer-push-registry-secret', variable: 'REGISTRY_SECRET')]) {
            stage('Delete Intermediate Tags') {
                shwrap("""
                    export STORAGE_DRIVER=vfs # https://github.com/coreos/fedora-coreos-pipeline/issues/723#issuecomment-1297668507
                    skopeo delete --authfile=\$REGISTRY_SECRET \
                    docker://${container_registry_staging_repo}:node-image-${RELEASE}-${shortcommit}
                """)
                parallel archinfo.keySet().collectEntries{arch -> [arch, {
                    shwrap("""
                        export STORAGE_DRIVER=vfs # https://github.com/coreos/fedora-coreos-pipeline/issues/723#issuecomment-1297668507
                        skopeo delete --authfile=\$REGISTRY_SECRET \
                        docker://${container_registry_staging_repo}:${params.RELEASE}-${arch}-${shortcommit}
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
            message = "build-node-image #${env.BUILD_NUMBER} <${env.BUILD_URL}|:jenkins:> <${env.RUN_DISPLAY_URL}|:ocean:> [${gitref}@${shortcommit}]"
            pipeutils.trySlackSend(message: message)
        }
    }
}}} // cosaPod, timeout, and lock finish here

