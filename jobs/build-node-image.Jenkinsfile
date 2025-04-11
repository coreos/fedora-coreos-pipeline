def commit, shortcommit

node {
    checkout scm
    // these are script global vars
    pipeutils = load("utils.groovy")
    pipecfg = pipeutils.load_pipecfg()
}

properties([
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
    timeout(time: 45, unit: 'MINUTES') {
    try {

        def output = shwrapCapture("git ls-remote ${src_config_url} ${src_config_ref}")
        commit = output.substring(0,40)
        shortcommit = commit.substring(0,7)
        build_description  = "${build_description} ${src_config_ref}@${shortcommit}"
        currentBuild.description = "${build_description} Running"

        // Get the list of requested architectures to build for
        def arches = params.ARCHES.split() as Set
        def archinfo = arches.collectEntries{[it, [:]]}
        def now = java.time.LocalDateTime.now()
        def timestamp = now.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmm"))
        def (registry_staging_repo, registry_staging_tags, registry_prod_repo, registry_prod_tags) = pipeutils.get_ocp_node_registry_repo(pipecfg, params.RELEASE, timestamp)

        // `staging_tags` is a list to stay consistent with the `prod` objects,
        // but we only need a single tag here since it's used solely for storing
        // intermediary images before they are referenced in a multi-arch manifest.
        def registry_staging_tag = registry_staging_tags[0]

        // add any additional root CA cert before we do anything that fetches
        pipeutils.addOptionalRootCA()

        def yumrepos_file
        def node_image_manifest_digest
        def extensions_image_manifest_digest
        stage('Init') {
            shwrap("git clone ${stream_info.yumrepo.url} yumrepos")
            for (repo in stream_info.yumrepo.files) {
                shwrap("cat yumrepos/${repo} >> all.repo")
            }
            yumrepos_file = shwrapCapture("realpath all.repo")
            // let's archive it also so it's easy to see what the final repo file looked like
            archiveArtifacts 'all.repo'
        }

        stage('Build Node Image') {
            withCredentials([file(credentialsId: 'oscontainer-push-registry-secret', variable: 'REGISTRY_AUTH_FILE')]) {
                 def build_from = params.FROM ?: stream_info.from
                 node_image_manifest_digest = pipeutils.build_and_push_image(arches: arches,
                                                src_commit: commit,
                                                src_url: src_config_url,
                                                staging_repository: registry_staging_repo,
                                                image_tag_staging: registry_staging_tag,
                                                manifest_tag_staging: "${registry_staging_tag}",
                                                secret: "id=yumrepos,src=${yumrepos_file}", // notsecret (for secret scanners)
                                                from: build_from,
                                                extra_build_args: ["--security-opt label=disable", "--mount-host-ca-certs", "--force"])
            }
        }
        stage('Build Extensions Image') {
            withCredentials([file(credentialsId: 'oscontainer-push-registry-secret', variable: 'REGISTRY_AUTH_FILE')]) {
                // Use the node image as from
                def build_from = "${registry_staging_repo}@${node_image_manifest_digest}"
                extensions_image_manifest_digest = pipeutils.build_and_push_image(arches: arches,
                                               src_commit: commit,
                                               src_url: src_config_url,
                                               staging_repository: registry_staging_repo,
                                               image_tag_staging: "${registry_staging_tag}-extensions",
                                               manifest_tag_staging: "${registry_staging_tag}-extensions",
                                               secret: "id=yumrepos,src=${yumrepos_file}", // notsecret (for secret scanners)
                                               from: build_from,
                                               extra_build_args: ["--security-opt label=disable", "--mount-host-ca-certs",
                                                                  "--git-containerfile", "extensions/Dockerfile", "--force"])
            }
        }
        stage("Release Manifests") {
            withCredentials([file(credentialsId: 'oscontainer-push-registry-secret', variable: 'REGISTRY_AUTH_FILE')]) {
                // copy the extensions first as the node image existing is a signal
                // that it's ready for release. So we want all the expected artifacts
                // to be available when the ART tooling kicks in.

                // Skopeo does not support pushing multiple tags at the same time
                // So we just recopy the same image multiple times.
                // https://github.com/containers/skopeo/issues/513
                for (tag in registry_prod_tags) {
                    pipeutils.copy_image("${registry_staging_repo}@${extensions_image_manifest_digest}",
                                     "${registry_prod_repo}:${tag}-extensions")
                }
                for (tag in registry_prod_tags) {
                    pipeutils.copy_image("${registry_staging_repo}@${node_image_manifest_digest}",
                                     "${registry_prod_repo}:${tag}")
                }
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
        message = ":openshift: build-node-image #${env.BUILD_NUMBER} <${env.BUILD_URL}|:jenkins:> <${env.RUN_DISPLAY_URL}|:ocean:> ${build_description}"
        pipeutils.trySlackSend(message: message)
    }
}}} // cosaPod, timeout, and lock finish here

