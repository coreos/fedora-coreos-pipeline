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
      choice(name: 'STREAM',
             choices: pipeutils.get_streams_choices(pipecfg),
             description: 'CoreOS stream to build'),
      string(name: 'VERSION',
             description: 'Build version',
             defaultValue: '',
             trim: true),
      string(name: 'FROM',
             description: 'Image FROM',
             defaultValue: '',
             trim: true),
      string(name: 'ARCHES',
             description: 'Space-separated list of target architectures',
             defaultValue: "x86_64 aarch64 ppc64le s390x",
             trim: true),
      string(name: 'OPENSHIFT_OS_GIT_URL',
             description: 'Override the coreos-assembler git repo to use',
             defaultValue: "https://github.com/openshift/os.git",
             trim: true),
      string(name: 'OPENSHIFT_OS_GIT_REF',
             description: 'Override the coreos-assembler git ref to use',
             defaultValue: "master",
             trim: true),
      string(name: 'CONTAINER_REGISTRY_REPO',
             description: 'Override the registry to push the container to',
             defaultValue: "quay.io/openshift-release-dev/ocp-v4.0-art-dev:node",
             trim: true),
      string(name: 'CONTAINER_REGISTRY_STAGING_REPO',
             description: 'Override the staging registry where intermediate images go',
            defaultValue: "registry.ci.openshift.org/node-staging",
             trim: true),
      string(name: 'COREOS_ASSEMBLER_IMAGE',
             description: 'Override the coreos-assembler image to use',
             defaultValue: "quay.io/coreos-assembler/coreos-assembler:latest",
             trim: true),
      booleanParam(name: 'MANIFEST_RELEASE',
                   defaultValue: false,
                   description: 'Publish the Manifest in the official repo'),
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
cosa_img = cosa_img ?: pipeutils.get_cosa_img(pipecfg, params.STREAM)

def stream_info = pipecfg.streams[params.STREAM]

def timeout_mins = 300

node {
    change = checkout(
        changelog: true,
        poll: false,
        scm: [
            $class: 'GitSCM',
            branches: [[name: "origin/${params.OPENSHIFT_OS_GIT_REF}"]],
            userRemoteConfigs: [[url: params.OPENSHIFT_OS_GIT_URL]],
            extensions: [[$class: 'CloneOption',
                          noTags: true,
                          reference: '',
                          shallow: true]]
        ]
    )

    gitref = params.OPENSHIFT_OS_GIT_REF
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

        currentBuild.description = "[${gitref}@${shortcommit}] Running"
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

        // add any additional root CA cert before we do anything that fetches
        pipeutils.addOptionalRootCA()

        def ref = pipeutils.get_source_config_ref_for_stream(pipecfg, params.STREAM)
        def src_config_commit
        if (params.SRC_CONFIG_COMMIT) {
            src_config_commit = params.SRC_CONFIG_COMMIT
        } else {
            src_config_commit = shwrapCapture("git ls-remote ${pipecfg.source_config.url} refs/heads/${ref} | cut -d \$'\t' -f 1")
        }

        stage('Init') {
            def yumrepos = pipecfg.source_config.yumrepos ? "--yumrepos ${pipecfg.source_config.yumrepos}" : ""
            def variant = stream_info.variant ? "--variant ${stream_info.variant}" : ""
            shwrap("""
            cosa init --force --branch ${ref} --commit=${src_config_commit} ${yumrepos} ${variant} ${pipecfg.source_config.url}
            """)
        }
        stage('Build Layered Image') {
            withCredentials([file(credentialsId: 'node-image-secrets', variable: 'REGISTRY_SECRET')]) {
                parallel arches.collectEntries{arch -> [arch, {
                    pipeutils.withPodmanRemoteArchBuilder(arch: arch) {
                        shwrap("""
                            cosa remote-build-container --arch $arch \
                                --git-ref $commit ${force} \
                                --git-url ${params.OPENSHIFT_OS_GIT_URL} \
                                --repo ${params.CONTAINER_REGISTRY_STAGING_REPO} \
                                --push-to-registry --auth=\${REGISTRY_SECRET} \
                                --secret id=yumrepos,src=\$(pwd)/src/yumrepos/rhel-9.6.repo \
                                --mount-host-ca-certs \
                                --security-opt label=disable \
                                --from ${params.FROM}
                        """)
                    }
                }]}
            }
        }
        withCredentials([file(credentialsId: 'node-image-secrets', variable: 'REGISTRY_SECRET')]) {
            stage("Push Manifest") {
                def manifest = "${params.CONTAINER_REGISTRY_STAGING_REPO}:node-image-${shortcommit}"
                def cmds = "/usr/bin/buildah manifest create ${manifest}"
                parallel archinfo.keySet().collectEntries{arch -> [arch, {
                    def image = "docker://${params.CONTAINER_REGISTRY_STAGING_REPO}:${arch}-${shortcommit}"
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
        if (params.MANIFEST_RELEASE) {
            withCredentials([file(credentialsId: 'node-image-secrets', variable: 'REGISTRY_SECRET')]) {
               stage("Release Manifest") {
                    // Release the manifest and container images
                    shwrap("""
                        skopeo copy --all --authfile \$REGISTRY_SECRET   \
                        docker://${params.CONTAINER_REGISTRY_STAGING_REPO}:node-image-${shortcommit} \
                        docker://${params.CONTAINER_REGISTRY_REPO}:node-image
                    """)
                }
            }
        }
        withCredentials([file(credentialsId: 'node-image-secrets', variable: 'REGISTRY_SECRET')]) {            
            stage('Delete Intermediate Tags') {
                if (params.MANIFEST_RELEASE) {
                    shwrap("""
                        export STORAGE_DRIVER=vfs # https://github.com/coreos/fedora-coreos-pipeline/issues/723#issuecomment-1297668507
                        skopeo delete --authfile=\$REGISTRY_SECRET \
                        docker://${params.CONTAINER_REGISTRY_STAGING_REPO}:node-image-${shortcommit}
                    """)
                }
                parallel archinfo.keySet().collectEntries{arch -> [arch, {
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
    } 
    }
}} // cosaPod, timeout, and lock finish here

