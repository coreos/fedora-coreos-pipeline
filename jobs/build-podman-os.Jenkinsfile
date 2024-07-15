def gitref, commit, shortcommit

def artifacts = [
    [platform: "qemu", suffix: "qcow2"],
    [platform: "applehv", suffix: "raw"],
    [platform: "hyperv", suffix: "vhdx"]
]

node {
    checkout scm
    // these are script global vars
    pipeutils = load("utils.groovy")
}

// Keep it here for now instead of utils
// since it is podman job only

def generate_disk_images(artifacts, shortcommit, arch, staging_repo, repo) {
    generate_diskvar_json(shortcommit, arch, artifacts, staging_repo, repo)
    // First create the qcow2 and store it in cache
    supermin_run("--cache", arch, "qcow2", shortcommit)
    def artifacts_runvm = artifacts.drop(1) // drop qcow2
    parallel artifacts_runvm.collectEntries{artifact -> [artifact["platform"], {
        supermin_run("--snapshot", arch, "${artifact["suffix"]}", shortcommit)
    }]}
}


def generate_diskvar_json(shortcommit, arch, artifacts, staging_repo, repo) {
    artifacts.each { artifact ->
        // Do not pass anything to extra-kargs-string for podman
        // See: https://github.com/coreos/fedora-coreos-pipeline/pull/975
        def jsonContent = """
        {
            "osname": "fedora-coreos",
            "ostree-container": "",
            "container-repo": "${staging_repo}",
            "container-tag": "${arch}-${shortcommit}",
            "extra-kargs-string": "",
            "image-type": "${artifact["platform"]}",
            "container-imgref": "ostree-remote-registry:fedora:${repo}:5.1",
            "metal-image-size": "3072",
            "cloud-image-size": "10240",
            "deploy-via-container": "true",
            "rootfs-size": "0"
        }
        """
        def file_path="./diskvars-${arch}-${artifact["suffix"]}.json"
        writeFile file: file_path, text: jsonContent
    }
    if (arch != "x86_64") {
        shwrap("""cosa remote-session sync ./diskvars-${arch}-*.json  {:,}""")
    }
}


def supermin_run(cache, arch, artifact, shortcommit) {
    shwrap("""cosa supermin-run ${cache} \
       /usr/lib/coreos-assembler/runvm-osbuild \
       --config ./diskvars-${arch}-${artifact}.json \
       --filepath podman-$arch-${shortcommit}.${artifact} \
       --mpp /usr/lib/coreos-assembler/osbuild-manifests/coreos.osbuild.${arch}.mpp.yaml
    """)
}


properties([
    pipelineTriggers([cron('H H * * *')]),
    parameters([
      string(name: 'ARCHES',
             description: 'Space-separated list of target architectures',
             defaultValue: "x86_64 aarch64",
             trim: true),
      string(name: 'PODMAN_MACHINE_GIT_URL',
             description: 'Override the coreos-assembler git repo to use',
             defaultValue: "https://github.com/containers/podman-machine-os",
             trim: true),
      string(name: 'PODMAN_MACHINE_GIT_REF',
             description: 'Override the coreos-assembler git ref to use',
             defaultValue: "main",
             trim: true),
      string(name: 'CONTAINER_REGISTRY_REPO',
             description: 'Override the registry to push the container to',
             defaultValue: "quay.io/podman/machine-os",
             trim: true),
      string(name: 'CONTAINER_REGISTRY_STAGING_REPO',
             description: 'Override the staging registry where intermediate images go',
             defaultValue: "quay.io/podman/machine-os-staging",
             trim: true),
      string(name: 'COREOS_ASSEMBLER_IMAGE',
             description: 'Override the coreos-assembler image to use',
             defaultValue: "quay.io/coreos-assembler/coreos-assembler:main",
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


node {
    change = checkout(
        changelog: true,
        poll: false,
        scm: [
            $class: 'GitSCM',
            branches: [[name: "origin/${params.PODMAN_MACHINE_GIT_REF}"]],
            userRemoteConfigs: [[url: params.PODMAN_MACHINE_GIT_URL]],
            extensions: [[$class: 'CloneOption',
                          noTags: true,
                          reference: '',
                          shallow: true]]
        ]
    )

    gitref = params.PODMAN_MACHINE_GIT_REF
    def output = shwrapCapture("git rev-parse HEAD")
    commit = output.substring(0,40)
    shortcommit = commit.substring(0,7)
}

lock(resource: "build-podman-os") {
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

        withCredentials([file(credentialsId: 'podman-push-registry-secret', variable: 'REGISTRY_SECRET')]) {
            stage('Build Layered Image') {
                 parallel arches.collectEntries{arch -> [arch, {
                     pipeutils.withPodmanRemoteArchBuilder(arch: arch) {
                         shwrap("""
                             cosa remote-build-container \
                                 --arch $arch --cache-ttl ${cacheTTL} \
                                 --git-ref $commit ${force} \
                                 --git-url ${params.PODMAN_MACHINE_GIT_URL} \
                                 --git-sub-dir podman-image-daily \
                                 --repo ${params.CONTAINER_REGISTRY_STAGING_REPO} \
                                 --push-to-registry --auth=\$REGISTRY_SECRET
                         """)
                     }
                 }]}
            }
        }
        // Initialize the sessions on the remote builders
        stage("Initialize Remotes") {
            parallel archinfo.keySet().collectEntries{arch -> [arch, {
                if (arch != "x86_64") {
                    pipeutils.withPodmanRemoteArchBuilder(arch: arch) {
                        archinfo[arch]['session'] = pipeutils.makeCosaRemoteSession(
                            expiration: "60m",
                            image: params.COREOS_ASSEMBLER_IMAGE,
                            workdir: WORKSPACE,
                        )
                    }
                }
            }]}
        }
        stage("Init") {
            parallel archinfo.keySet().collectEntries{arch -> [arch, {
                pipeutils.withOptionalExistingCosaRemoteSession(
                arch: arch, session: archinfo[arch]['session']) {
                    shwrap("""cosa init --force https://github.com/coreos/fedora-coreos-config.git""")
                }
            }]}
        }
        stage("Build Disk Images") {
            parallel archinfo.keySet().collectEntries{arch -> [arch, {
                pipeutils.withOptionalExistingCosaRemoteSession(
                arch: arch, session: archinfo[arch]['session']) {
                    generate_disk_images(artifacts, shortcommit, arch, params.CONTAINER_REGISTRY_STAGING_REPO,
                    params.CONTAINER_REGISTRY_REPO)
                }
            }]}
        }
        stage("Kola") {
            parallel archinfo.keySet().collectEntries{arch -> [arch, {
                pipeutils.withOptionalExistingCosaRemoteSession(
                    arch: arch, session: archinfo[arch]['session']) {
                    shwrap("""cosa kola run  basic* --qemu-image=./podman-${arch}-${shortcommit}.qcow2""")
                }
            }]}
        }
        stage("Compress Artifacts") {
            parallel archinfo.keySet().collectEntries{arch -> [arch, {
                pipeutils.withOptionalExistingCosaRemoteSession(
                arch: arch, session: archinfo[arch]['session']) {
                    parallel artifacts.collectEntries{artifact -> [artifact["platform"] , {
                        def file = "podman-$arch-${shortcommit}.${artifact["suffix"]}"
                        shwrap("""cosa shell -- /usr/bin/zstd -14 $file""")
                    }]}
                }
            }]}
        }
        stage("Sync Artifacts") {
            parallel archinfo.keySet().collectEntries{arch -> [arch, {
                pipeutils.withOptionalExistingCosaRemoteSession(
                arch: arch, session: archinfo[arch]['session']) {
                    if (arch != "x86_64") {
                        parallel artifacts.collectEntries{artifact -> [artifact["platform"] , {
                            shwrap("""cosa remote-session sync {:,}podman-$arch-${shortcommit}.${artifact["suffix"]}.zst """)
                        }]}
                    }
                }
            }]}
        }
        withCredentials([file(credentialsId: 'podman-push-registry-secret', variable: 'REGISTRY_SECRET')]) {
            stage("Push Manifest") {
                def manifest = "${params.CONTAINER_REGISTRY_STAGING_REPO}:5.1-${shortcommit}"
                def cmds = "/usr/bin/buildah manifest create ${manifest}"
                parallel archinfo.keySet().collectEntries{arch -> [arch, {
                    def image = "docker://${params.CONTAINER_REGISTRY_STAGING_REPO}:${arch}-${shortcommit}"
                    cmds += " && /usr/bin/buildah manifest add ${manifest} ${image}"
                    artifacts.each { artifact ->
                        def file = "podman-$arch-${shortcommit}.${artifact["suffix"]}.zst"
                        def platform = "${artifact["platform"]}"
                        cmds += " && /usr/bin/buildah manifest add --artifact --artifact-type='' \
                                --os=linux --arch=${arch} --annotation 'disktype=${platform}' ${manifest} ${file}"
                    }
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
            withCredentials([file(credentialsId: 'podman-push-registry-secret', variable: 'REGISTRY_SECRET')]) {
               stage("Release Manifest") {
                    // Release the manifest and container images
                    shwrap("""
                        skopeo copy --all --authfile \$REGISTRY_SECRET   \
                        docker://${params.CONTAINER_REGISTRY_STAGING_REPO}:5.1-${shortcommit} \
                        docker://${params.CONTAINER_REGISTRY_REPO}:5.1
                    """)
                }
            }
        }
        withCredentials([file(credentialsId: 'podman-push-registry-secret', variable: 'REGISTRY_SECRET')]) {            
            stage('Delete Intermediate Tags') {
                if (params.MANIFEST_RELEASE) {
                    shwrap("""
                        export STORAGE_DRIVER=vfs # https://github.com/coreos/fedora-coreos-pipeline/issues/723#issuecomment-1297668507
                        skopeo delete --authfile=\$REGISTRY_SECRET \
                        docker://${params.CONTAINER_REGISTRY_STAGING_REPO}:5.1-${shortcommit}
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
        // Destroy the remote sessions. We don't need them anymore
        stage("Destroy Remotes") {
            parallel archinfo.keySet().collectEntries{arch -> [arch, {
                if (arch != "x86_64") {
                    pipeutils.withExistingCosaRemoteSession(
                        arch: arch, session: archinfo[arch]['session']) {
                        shwrap("cosa remote-session destroy")
                    }
                }
            }]}
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
            message = "build-podman-os #${env.BUILD_NUMBER} <${env.BUILD_URL}|:jenkins:> <${env.RUN_DISPLAY_URL}|:ocean:> [${gitref}@${shortcommit}]"
            pipeutils.trySlackSend(message: message)
        }
    }
}}} // cosaPod, timeout, and lock finish here

