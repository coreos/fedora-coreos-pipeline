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
def skip_brew_upload = stream_info.skip_brew_upload ?: false
def src_config_ref = stream_info.source_config.ref
def src_config_url = stream_info.source_config.url

def basearches = params.ARCHES.split() as Set
def timeout_mins = 300

def cosa_img = params.COREOS_ASSEMBLER_IMAGE

// Get the tag that's unique
def unique_tag = ""

lock(resource: "build-node-image") {
    // building actually happens on builders so we don't need much resources
    cosaPod(image: params.COREOS_ASSEMBLER_IMAGE,
            memory: "2.5Gi", kvm: true,
            serviceAccount: "jenkins",
            secrets: ["brew-keytab", "brew-ca:ca.crt:/etc/pki/ca.crt",
                      "koji-conf:koji.conf:/etc/koji.conf",
                      "krb5-conf:krb5.conf:/etc/krb5.conf"]) {
    timeout(time: 45, unit: 'MINUTES') {

        def registry_staging_repo
        def registry_staging_tags
        def registry_prod_repo
        def registry_prod_tags
        def node_image_manifest_digest
        def extensions_image_manifest_digest

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
        (registry_staging_repo, registry_staging_tags, registry_prod_repo, registry_prod_tags) = pipeutils.get_ocp_node_registry_repo(pipecfg, params.RELEASE, timestamp)

        for (tag in registry_prod_tags) {
            if (tag.contains(timestamp)) {
                if (unique_tag != "") {
                    error("Multiple unique tags found: ${registry_prod_tags}")
                }
                unique_tag = tag
            }
        }

        // `staging_tags` is a list to stay consistent with the `prod` objects,
        // but we only need a single tag here since it's used solely for storing
        // intermediary images before they are referenced in a multi-arch manifest.
        def registry_staging_tag = registry_staging_tags[0]

        def v2s2 = pipecfg.ocp_node_builds.registries.prod.v2s2 ?: false
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

        stage('Build Node Image') {
            withCredentials([file(credentialsId: 'oscontainer-push-registry-secret', variable: 'REGISTRY_AUTH_FILE')]) {
                 def build_from = params.FROM ?: stream_info.from
                 def label_args = []
                 if (unique_tag != "") {
                     label_args = ["--label", "coreos.build.manifest-list-tag=${unique_tag}"]
                 }

                 node_image_manifest_digest = pipeutils.build_and_push_image(arches: arches,
                                                src_commit: commit,
                                                src_url: src_config_url,
                                                staging_repository: registry_staging_repo,
                                                image_tag_staging: registry_staging_tag,
                                                manifest_tag_staging: "${registry_staging_tag}",
                                                secret: "id=yumrepos,src=${yumrepos_file}", // notsecret (for secret scanners)
                                                from: build_from,
                                                v2s2: v2s2,
                                                extra_build_args: ["--security-opt label=disable", "--mount-host-ca-certs", "--force",
                                                                   "--add-openshift-build-labels"] + label_args)
            }
        }
        stage('Build Extensions Image') {
            withCredentials([file(credentialsId: 'oscontainer-push-registry-secret', variable: 'REGISTRY_AUTH_FILE')]) {
                // Use the node image as from
                def build_from = "${registry_staging_repo}@${node_image_manifest_digest}"
                def label_args = []
                if (unique_tag != "") {
                    label_args = ["--label", "coreos.build.manifest-list-tag=${unique_tag}-extensions"]
                }
                extensions_image_manifest_digest = pipeutils.build_and_push_image(arches: arches,
                                               src_commit: commit,
                                               src_url: src_config_url,
                                               staging_repository: registry_staging_repo,
                                               image_tag_staging: "${registry_staging_tag}-extensions",
                                               manifest_tag_staging: "${registry_staging_tag}-extensions",
                                               secret: "id=yumrepos,src=${yumrepos_file}", // notsecret (for secret scanners)
                                               from: build_from,
                                               v2s2: v2s2,
                                               extra_build_args: ["--security-opt label=disable", "--mount-host-ca-certs",
                                                                  "--git-containerfile", "extensions/Dockerfile", "--force",
                                                                  "--add-openshift-build-labels"] + label_args)
            }
        }
        stage("Run Tests") {
            withCredentials([file(credentialsId: 'oscontainer-push-registry-secret', variable: 'REGISTRY_AUTH_FILE')]) {
                def openshift_stream = params.RELEASE.split("-")[0]
                def rhel_stream = "rhel-" + params.RELEASE.split("-")[1]

                parallel basearches.collectEntries { arch ->
                    [arch, {
                        // Define the sequence of cosa commands as a closure to avoid repetition.
                        def executeCosaCommands = { boolean isRemote ->
                            // The 'cosa init' command can exit with an error due to a known issue (coreos/coreos-assembler#4239).
                            // Piping to 'true' ignores any non-zero exit code from 'cosa init', preventing the pipeline from failing.
                            shwrap("""
                                set +o pipefail
                                cosa init https://github.com/openshift/os --branch release-${openshift_stream} --force | true
                            """)
                            // The 'cosa shell' prefix directs commands to the correct execution environment:
                            // the remote session if active, or the local container otherwise.
                            def s3_dir = pipeutils.get_s3_streams_dir(pipecfg, rhel_stream)
                            pipeutils.shwrapWithAWSBuildUploadCredentials("""
                                cosa shell mkdir -p tmp
                                cosa buildfetch \
                                    --arch=$arch --artifact qemu --url=s3://${s3_dir}/builds \
                                    --aws-config-file \${AWS_BUILD_UPLOAD_CONFIG} --find-build-for-arch
                            """)

                            def build_id = shwrapCapture("""
                                link=\$(cosa shell realpath builds/latest)
                                cosa shell basename \$link
                            """)
                            shwrap("cosa decompress --build $build_id")
                            def skopeo_arch_override = pipeutils.rpm_to_go_arch(arch)
                            shwrap("cosa shell skopeo copy --override-arch ${skopeo_arch_override} --authfile $REGISTRY_AUTH_FILE docker://${registry_staging_repo}@${node_image_manifest_digest} oci-archive:./openshift-${arch}.ociarchive")
                            kola(
                                cosaDir: WORKSPACE,
                                build: build_id,
                                arch: arch,
                                skipUpgrade: true,
                                extraArgs: "--tag openshift --oscontainer openshift-${arch}.ociarchive --denylist-stream ${params.RELEASE}"
                            )
                            kola(
                                cosaDir: WORKSPACE,
                                build: build_id,
                                arch: arch,
                                skipUpgrade: true,
                                extraArgs: "-b rhcos --tag openshift --oscontainer openshift-${arch}.ociarchive --denylist-stream ${params.RELEASE}"
                            )
                        }

                        // Conditional execution based on architecture
                        if (arch != 'x86_64') {
                            // Conditionally create the remote session only if the architecture is NOT x86_64.
                            pipeutils.withPodmanRemoteArchBuilder(arch: arch) {
                                def session = pipeutils.makeCosaRemoteSession(
                                    expiration: "${timeout_mins}m",
                                    image: cosa_img,
                                    workdir: WORKSPACE
                                )
                                withEnv(["COREOS_ASSEMBLER_REMOTE_SESSION=${session}"]) {
                                    // Execute the commands within the remote session context.
                                    executeCosaCommands(true)
                                }
                            }
                        } else {
                            // For x86_64, execute the commands directly without a remote session.
                            executeCosaCommands(false)
                        }
                    }]
                }
            }
        }
        if (!skip_brew_upload){
            stage("Brew Upload") {
                // Use the staging since we already have the digests
                pipeutils.brew_upload(arches, params.RELEASE, registry_staging_repo, node_image_manifest_digest,
                                      extensions_image_manifest_digest, timestamp, pipecfg)
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
        def message = ":openshift:"
        if (currentBuild.result == 'SUCCESS') {
            currentBuild.description = "${build_description} ⚡"
            message = "${message} :sparkles:"
        } else {
            currentBuild.description = "${build_description} ❌"
            message = "${message} :fire:"
        }

        def unique_tag_display = unique_tag ? "(${unique_tag})" : ""

        if (unique_tag != "") {
            node_ref = ":${unique_tag}"
            extensions_ref = ":${unique_tag}-extensions"
            registry_repo = registry_prod_repo
        } else {
            node_ref = "@${node_image_manifest_digest}"
            extensions_ref = "@${extensions_image_manifest_digest}"
            registry_repo = registry_staging_repo
        }

        def pullspec_links = " <https://${registry_repo}${node_ref}|:node:> <https://${registry_repo}${extensions_ref}|:puzzle-piece:>"

        message = "${message} build-node-image #${env.BUILD_NUMBER} <${env.BUILD_URL}|:jenkins:> <${env.RUN_DISPLAY_URL}|:ocean:> ${build_description}${unique_tag_display} ${pullspec_links}"
        pipeutils.trySlackSend(message: message)
    }
}}} // cosaPod, timeout, and lock finish here
