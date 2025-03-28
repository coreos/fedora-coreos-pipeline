def commit, shortcommit

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
    ]+ pipeutils.add_hotfix_parameters_if_supported()),
    buildDiscarder(logRotator(
        numToKeepStr: '100',
        artifactNumToKeepStr: '100'
    )),
    durabilityHint('PERFORMANCE_OPTIMIZED')
])

def build_description = "[${params.RELEASE}]"

// Reload pipecfg if a hotfix build was provided. The reason we do this here
// instead of loading the right one upfront is so that we don't modify the
// parameter definitions above and their default values.
if (params.PIPECFG_HOTFIX_REPO || params.PIPECFG_HOTFIX_REF) {
    node {
        pipecfg = pipeutils.load_pipecfg(params.PIPECFG_HOTFIX_REPO, params.PIPECFG_HOTFIX_REF)
        build_description = "[${params.RELEASE}-${pipecfg.hotfix.name}]"
    }
}

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
        build_description  = "${build_description} ${src_config_ref}@${shortcommit}"
        currentBuild.description = "${build_description} Running"

        // Get the list of requested architectures to build for
        def arches = params.ARCHES.split() as Set
        def archinfo = arches.collectEntries{[it, [:]]}
        def (container_registry_staging_repo, container_registry_repo_and_tag) = pipeutils.get_ocp_node_registry_repo(pipecfg, params.RELEASE)
        def container_registry_staging_manifest_tag = "${params.RELEASE}"
        def container_registry_staging_image_tag = "${params.RELEASE}"
        def container_registry_staging_manifest = "${container_registry_staging_repo}:${container_registry_staging_manifest_tag}"

        // add any additional root CA cert before we do anything that fetches
        pipeutils.addOptionalRootCA()

        def yumrepos_file
        stage('Init') {
            shwrap("git clone ${stream_info.yumrepo.url} yumrepos")
            for (repo in stream_info.yumrepo.files) {
                shwrap("cat yumrepos/${repo} >> all.repo")
            }
            yumrepos_file = shwrapCapture("realpath all.repo")
            // let's archive it also so it's easy to see what the final repo file looked like
            archiveArtifacts 'all.repo'
        }

        if (params.PIPECFG_HOTFIX_REPO || params.PIPECFG_HOTFIX_REF) {
            container_registry_staging_image_tag += "-hotfix-${pipecfg.hotfix.name}"
        }
        stage('Build Node Image') {
            withCredentials([file(credentialsId: 'oscontainer-push-registry-secret', variable: 'REGISTRY_AUTH_FILE')]) {
                 def build_from = params.FROM ?: stream_info.from
                 pipeutils.build_and_push_image(arches: arches,
                                                src_commit: commit,
                                                src_url: src_config_url,
                                                staging_repository: container_registry_staging_repo,
                                                image_tag_staging: container_registry_staging_image_tag,
                                                manifest_tag_staging: container_registry_staging_manifest_tag,
                                                secret: "id=yumrepos,src=${yumrepos_file}",
                                                from: build_from,
                                                extra_build_args: ["--security-opt label=disable", "--mount-host-ca-certs", "--force"])
            }
        }
        stage('Build Extensions Image') {
            withCredentials([file(credentialsId: 'oscontainer-push-registry-secret', variable: 'REGISTRY_AUTH_FILE')]) {
                // Use the node image as from
                def build_from = container_registry_staging_manifest
                pipeutils.build_and_push_image(arches: arches,
                                               src_commit: commit,
                                               src_url: src_config_url,
                                               staging_repository: container_registry_staging_repo,
                                               image_tag_staging: "${container_registry_staging_image_tag}-extensions",
                                               manifest_tag_staging: "${container_registry_staging_manifest_tag}-extensions",
                                               secret: "id=yumrepos,src=${yumrepos_file}",
                                               from: build_from,
                                               extra_build_args: ["--security-opt label=disable", "--mount-host-ca-certs",
                                                                  "--git-containerfile", "extensions/Dockerfile", "--force"])
            }
        }
        stage("Release Manifests") {
            withCredentials([file(credentialsId: 'oscontainer-push-registry-secret', variable: 'REGISTRY_AUTH_FILE')]) {
                pipeutils.copy_image(container_registry_staging_manifest, container_registry_repo_and_tag)
                pipeutils.copy_image("${container_registry_staging_manifest}-extensions",
                                     "${container_registry_repo_and_tag}-extensions")
            }
        }
        currentBuild.result = 'SUCCESS'
    } catch (e) {
        currentBuild.result = 'FAILURE'
        throw e
    } finally {
        if (currentBuild.result == 'SUCCESS') {
            currentBuild.description = "${build_description} ⚡"
        } else {
            currentBuild.description = "${build_description} ❌"
        }
        if (currentBuild.result != 'SUCCESS') {
            message = ":openshift: build-node-image #${env.BUILD_NUMBER} <${env.BUILD_URL}|:jenkins:> <${env.RUN_DISPLAY_URL}|:ocean:> ${build_description}"
            pipeutils.trySlackSend(message: message)
        }
    }
}}} // cosaPod, timeout, and lock finish here

