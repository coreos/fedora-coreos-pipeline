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
    ]),
    buildDiscarder(logRotator(
        numToKeepStr: '100',
        artifactNumToKeepStr: '100'
    )),
    durabilityHint('PERFORMANCE_OPTIMIZED')
])

def stream_info = pipecfg.ocp_node_builds.release[params.RELEASE]
def src_config_ref = stream_info.source_config.ref
def src_config_url = stream_info.source_config.url

lock(resource: "build-node-image") {
    cosaPod(image: params.COREOS_ASSEMBLER_IMAGE,
            memory: "512Mi", kvm: false,
            serviceAccount: "jenkins") {
    timeout(time: 15, unit: 'MINUTES') {
    try {

        def output = shwrapCapture("git ls-remote ${src_config_url} ${src_config_ref}")
        commit = output.substring(0,40)
        shortcommit = commit.substring(0,7)
        currentBuild.description = "[${params.RELEASE}@${shortcommit}] Running"

        // Get the list of requested architectures to build for
        def arches = params.ARCHES.split() as Set
        def archinfo = arches.collectEntries{[it, [:]]}
        def container_registry_staging_repo = pipecfg.ocp_node_builds.registries.staging
        def container_registry_repo_and_tag = utils.substituteStr(pipecfg.ocp_node_builds.registries.prod,
                                                                 [RELEASE: params.RELEASE])

        // add any additional root CA cert before we do anything that fetches
        pipeutils.addOptionalRootCA()

        stage('Init') {
            shwrap("""git clone ${stream_info.yumrepo.url} yumrepos""")
        }

        stage('Build Layered Image') {
            withCredentials([file(credentialsId: 'oscontainer-push-registry-secret', variable: 'REGISTRY_SECRET')]) {
                def build_from = params.FROM ?: stream_info.from
                parallel arches.collectEntries{arch -> [arch, {
                    pipeutils.withPodmanRemoteArchBuilder(arch: arch) {
                        shwrap("""
                            cosa remote-build-container --arch $arch \
                                --git-ref $commit --force \
                                --git-url ${src_config_url} \
                                --repo ${container_registry_staging_repo} \
                                --push-to-registry --auth=\${REGISTRY_SECRET} \
                                --secret id=yumrepos,src=\$(pwd)/yumrepos/${stream_info.yumrepo.file} \
                                --mount-host-ca-certs \
                                --security-opt label=disable \
                                --from ${build_from} \
                                --tag ${params.RELEASE}-${arch}-${shortcommit}
                        """)
                    }
                }]}
            }
        }
        withCredentials([file(credentialsId: 'oscontainer-push-registry-secret', variable: 'REGISTRY_SECRET')]) {
            stage("Push Manifest") {
                def images = ""
                for (arch in arches) {
                    images += " --image=docker://${container_registry_staging_repo}:${params.RELEASE}-${arch}-${shortcommit}"
                }
                // arbitrarily selecting the s390x builder; we don't run this
                // locally because podman wants user namespacing (yes, even just
                // to push a manifest...)
                pipeutils.withPodmanRemoteArchBuilder(arch: "s390x") {
                    shwrap("""
                    cosa push-container-manifest \
                        --auth=\$REGISTRY_SECRET --tag ${params.RELEASE}-${shortcommit} \
                        --repo ${container_registry_staging_repo} ${images}
                    """)
                }
            }
        }
        withCredentials([file(credentialsId: 'oscontainer-push-registry-secret', variable: 'REGISTRY_SECRET')]) {
           stage("Release Manifest") {
                // Release the manifest and container images
                shwrap("""
                    skopeo copy --all --authfile \$REGISTRY_SECRET \
                        docker://${container_registry_staging_repo}:${params.RELEASE}-${shortcommit} \
                        docker://${container_registry_repo_and_tag}
                """)
            }
        }
        withCredentials([file(credentialsId: 'oscontainer-push-registry-secret', variable: 'REGISTRY_SECRET')]) {
            stage('Delete Intermediate Tags') {
                shwrap("""
                    export STORAGE_DRIVER=vfs # https://github.com/coreos/fedora-coreos-pipeline/issues/723#issuecomment-1297668507
                    skopeo delete --authfile=\$REGISTRY_SECRET \
                        docker://${container_registry_staging_repo}:${params.RELEASE}-${shortcommit}
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
            message = ":openshift: build-node-image #${env.BUILD_NUMBER} <${env.BUILD_URL}|:jenkins:> <${env.RUN_DISPLAY_URL}|:ocean:> [${params.RELEASE}][${src_config_ref}@${shortcommit}]"
            pipeutils.trySlackSend(message: message)
        }
    }
}}} // cosaPod, timeout, and lock finish here

