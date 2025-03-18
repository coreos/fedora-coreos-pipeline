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
        def container_registry_staging_manifest_tag = "${params.RELEASE}-${shortcommit}"
        def container_registry_staging_image_tag = "${params.RELEASE}-ARCH_REPLACE-${shortcommit}"
        // add any additional root CA cert before we do anything that fetches
        pipeutils.addOptionalRootCA()

        stage('Init') {
            shwrap("""git clone ${stream_info.yumrepo.url} yumrepos""")
        }

        stage('Build Layered Image') {
            def build_from = params.FROM ?: stream_info.from
            pipeutils.build_remote_image(arches, commit, src_config_url, container_registry_staging_repo,
                                         container_registry_staging_image_tag, stream_info.yumrepo.file, build_from,
                                          ["--security-opt label=disable", "--mount-host-ca-certs", "--force"])
        }

        stage('Build Extension Container') {
            pipeutils.build_remote_image(arches, commit, src_config_url, container_registry_staging_repo,
                                         "${container_registry_staging_image_tag}-extensions", stream_info.yumrepo.file, false,
                                         ["--security-opt label=disable", "--mount-host-ca-certs", "--force",
                                          "--git-file extensions/Dockerfile"] )
        }

        stage("Push Manifests") {
            pipeutils.push_manifest(arches, container_registry_staging_repo, container_registry_staging_image_tag,
                                    container_registry_staging_manifest_tag)
            pipeutils.push_manifest(arches, container_registry_staging_repo, "${container_registry_staging_image_tag}-extensions",
                                    "${container_registry_staging_manifest_tag}-extensions")
        }

        stage("Release Manifests") {
            pipeutils.release_manifest(container_registry_staging_repo, container_registry_staging_manifest_tag,
                                       container_registry_repo_and_tag)
            pipeutils.release_manifest(container_registry_staging_repo, "${container_registry_staging_manifest_tag}-extensions",
                                       "${container_registry_repo_and_tag}-extensions")
        }

        stage('Delete Intermediate Tags') {
            pipeutils.delete_tags(archinfo, container_registry_staging_repo, container_registry_staging_image_tag,
                                  container_registry_staging_manifest_tag)
            pipeutils.delete_tags(archinfo, container_registry_staging_repo, "${container_registry_staging_image_tag-extensions}",
                                  "${container_registry_staging_manifest_tag-extensions")
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

