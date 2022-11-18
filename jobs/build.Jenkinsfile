import org.yaml.snakeyaml.Yaml;

def pipeutils, pipecfg, uploading, libcloud
node {
    checkout scm
    pipeutils = load("utils.groovy")
    pipecfg = pipeutils.load_pipecfg()
    libcloud = load("libcloud.groovy")
}

// Base URL through which to download artifacts
BUILDS_BASE_HTTP_URL = "https://builds.coreos.fedoraproject.org/prod/streams"

properties([
    pipelineTriggers([]),
    parameters([
      choice(name: 'STREAM',
             choices: pipeutils.get_streams_choices(pipecfg),
             description: 'CoreOS stream to build'),
      string(name: 'VERSION',
             description: 'Override default versioning mechanism',
             defaultValue: '',
             trim: true),
      string(name: 'ADDITIONAL_ARCHES',
             description: "Override additional architectures (space-separated). " +
                          "Supported: ${pipeutils.get_supported_additional_arches().join(' ')}",
             defaultValue: "",
             trim: true),
      booleanParam(name: 'FORCE',
                   defaultValue: false,
                   description: 'Whether to force a rebuild'),
      booleanParam(name: 'EARLY_ARCH_JOBS',
                   defaultValue: true,
                   description: "Fork off the multi-arch jobs before all tests have run"),
      booleanParam(name: 'ALLOW_KOLA_UPGRADE_FAILURE',
                   defaultValue: false,
                   description: "Don't error out if upgrade tests fail (temporary)"),
      booleanParam(name: 'CLOUD_REPLICATION',
                   defaultValue: false,
                   description: 'Force cloud image replication for non-production'),
      string(name: 'COREOS_ASSEMBLER_IMAGE',
             description: 'Override coreos-assembler image to use',
             defaultValue: "",
             trim: true),
      booleanParam(name: 'KOLA_RUN_SLEEP',
                   defaultValue: false,
                   description: 'Wait forever at kola tests stage. Implies NO_UPLOAD'),
      booleanParam(name: 'NO_UPLOAD',
                   defaultValue: false,
                   description: 'Do not upload results to S3; for debugging purposes.'),
    ] + pipeutils.add_hotfix_parameters_if_supported()),
    buildDiscarder(logRotator(
        numToKeepStr: '100',
        artifactNumToKeepStr: '100'
    )),
    durabilityHint('PERFORMANCE_OPTIMIZED')
])

def build_description = "[${params.STREAM}]"

// Reload pipecfg if a hotfix build was provided. The reason we do this here
// instead of loading the right one upfront is so that we don't modify the
// parameter definitions above and their default values.
if (params.PIPECFG_HOTFIX_REPO || params.PIPECFG_HOTFIX_REF) {
    node {
        pipecfg = pipeutils.load_pipecfg(params.PIPECFG_HOTFIX_REPO, params.PIPECFG_HOTFIX_REF)
        build_description = "[${params.STREAM}-${pipecfg.hotfix.name}]"
    }
}

// runtime parameter always wins
def cosa_img = params.COREOS_ASSEMBLER_IMAGE
cosa_img = cosa_img ?: pipeutils.get_cosa_img(pipecfg, params.STREAM)
def additional_arches = params.ADDITIONAL_ARCHES.split()
additional_arches = additional_arches ?: pipeutils.get_additional_arches(pipecfg, params.STREAM)

def stream_info = pipecfg.streams[params.STREAM]

// If we are a mechanical stream then we can pin packages but we
// don't maintin complete lockfiles so we can't build in strict mode.
def strict_build_param = stream_info.type == "mechanical" ? "" : "--strict"

// Note the supermin VM just uses 2G. The really hungry part is xz, which
// without lots of memory takes lots of time. For now we just hardcode these
// here; we can look into making them configurable through the template if
// developers really need to tweak them.
// XXX bump an extra 2G (to 10.5) because of an error we are seeing in
// testiso: https://github.com/coreos/fedora-coreos-tracker/issues/1339
def cosa_memory_request_mb = 10.5 * 1024 as Integer

// Now that we've established the memory constraint based on xz above, derive
// kola parallelism from that. We leave 512M for overhead and VMs are at most
// 1.5G each (in general; root reprovisioning tests require 4G which is partly
// why we run them separately).
// XXX: https://github.com/coreos/coreos-assembler/issues/3118 will make this
// cleaner
def ncpus = ((cosa_memory_request_mb - 512) / 1536) as Integer

echo "Waiting for build-${params.STREAM} lock"
currentBuild.description = "${build_description} Waiting"

// declare these early so we can use them in `finally` block
def newBuildID, basearch

lock(resource: "build-${params.STREAM}") {
    timeout(time: 240, unit: 'MINUTES') {
    cosaPod(cpu: "${ncpus}",
            memory: "${cosa_memory_request_mb}Mi",
            image: cosa_img,
            serviceAccount: "jenkins") {
    try {

        basearch = shwrapCapture("cosa basearch")
        build_description += "[${basearch}]"
        currentBuild.description = "${build_description} Running"

        // this is defined IFF we *should* and we *can* upload to S3
        def s3_stream_dir

        if (pipecfg.s3 && pipeutils.AWSBuildUploadCredentialExists()) {
            s3_stream_dir = pipeutils.get_s3_streams_dir(pipecfg, params.STREAM)
        }

        // Now, determine if we should do any uploads to remote s3 buckets or clouds
        // Don't upload if the user told us not to or we're debugging with KOLA_RUN_SLEEP
        if (s3_stream_dir && (!params.NO_UPLOAD || params.KOLA_RUN_SLEEP)) {
            uploading = true
        } else {
            uploading = false
        }

        // add any additional root CA cert before we do anything that fetches
        pipeutils.addOptionalRootCA()

        def ref = pipeutils.get_source_config_ref_for_stream(pipecfg, params.STREAM)
        def src_config_commit = shwrapCapture("git ls-remote ${pipecfg.source_config.url} ${ref} | cut -d \$'\t' -f 1")

        stage('Init') {
            if (pipecfg.hacks?.use_yumrepos_branch_workaround) {
                // Right now the git repo that has our yum repo files isn't a
                // single branch, but a branch per stream. So let's work with
                // that here.
                def yumrepos_ref = params.STREAM
                if (yumrepos_ref == '4.13') {
                    yumrepos_ref = 'master'
                }
                shwrap("""
                cosa shell -- mkdir ./yumrepos
                cosa shell -- git clone --depth=1 --branch=${yumrepos_ref} ${pipecfg.source_config.yumrepos} ./yumrepos
                yumrepos=\$(cosa shell -- readlink -f ./yumrepos)
                cosa init --force --branch ${ref} --commit=${src_config_commit} --yumrepos=\${yumrepos} ${pipecfg.source_config.url}
                """)
            } else {
                def yumrepos = pipecfg.source_config.yumrepos ? "--yumrepos ${pipecfg.source_config.yumrepos}" : ""
                shwrap("""
                cosa init --force --branch ${ref} --commit=${src_config_commit} ${yumrepos} ${pipecfg.source_config.url}
                """)
            }

            // for now, just use the PVC to keep cache.qcow2 in a stream-specific dir
            def cache_img = "/srv/prod/${params.STREAM}/cache.qcow2"
            shwrap("""
            mkdir -p \$(dirname ${cache_img})
            ln -s ${cache_img} cache/cache.qcow2
            """)

            // If the cache img is larger than 7G, then nuke it. Otherwise
            // it'll just keep growing and we'll hit ENOSPC. It'll get rebuilt.
            shwrap("""
            if [ -f ${cache_img} ] && [ \$(du ${cache_img} | cut -f1) -gt \$((1024*1024*7)) ]; then
                rm -vf ${cache_img}
            fi
            """)
        }

        // Determine parent version/commit information
        def parent_version = ""
        def parent_commit = ""
        if (s3_stream_dir) {
            pipeutils.aws_s3_cp_allow_noent("s3://${s3_stream_dir}/releases.json", "releases.json")
            if (utils.pathExists("releases.json")) {
                def releases = readJSON file: "releases.json"
                // check if there's a previous release we should use as parent
                for (release in releases["releases"].reverse()) {
                    def commit_obj = release["commits"].find{ commit -> commit["architecture"] == basearch }
                    if (commit_obj != null) {
                        parent_commit = commit_obj["checksum"]
                        parent_version = release["version"]
                        break
                    }
                }
            }
        }

        // buildfetch previous build info
        stage('BuildFetch') {
            if (s3_stream_dir) {
                pipeutils.shwrapWithAWSBuildUploadCredentials("""
                cosa buildfetch --arch=${basearch} \
                    --url s3://${s3_stream_dir}/builds \
                    --aws-config-file \${AWS_BUILD_UPLOAD_CONFIG}
                """)
                if (parent_version != "") {
                    // also fetch the parent version; this is used by cosa to do the diff
                    pipeutils.shwrapWithAWSBuildUploadCredentials("""
                    cosa buildfetch --arch=${basearch} \
                        --build ${parent_version} \
                        --url s3://${s3_stream_dir}/builds \
                        --aws-config-file \${AWS_BUILD_UPLOAD_CONFIG}
                    """)
                }
            }
        }


        def prevBuildID = null
        if (utils.pathExists("builds/latest")) {
            prevBuildID = shwrapCapture("readlink builds/latest")
        }

        def new_version = ""
        if (params.VERSION) {
            new_version = params.VERSION
        } else if (pipecfg.versionary_hack) {
            new_version = shwrapCapture("/usr/lib/coreos-assembler/fcos-versionary")
        }

        // fetch from repos for the current build
        stage('Fetch') {
            shwrap("""
            cosa fetch ${strict_build_param}
            """)
        }

        stage('Build OSTree') {
            def parent_arg = ""
            if (parent_version != "") {
                parent_arg = "--parent-build ${parent_version}"
            }
            def version = new_version ? "--version ${new_version}" : ""
            def force = params.FORCE ? "--force" : ""
            shwrap("""
            cosa build ostree ${strict_build_param} --skip-prune ${force} ${version} ${parent_arg}
            """)

            // Insert the parent info into meta.json so we can display it in
            // the release browser and for sanity checking
            if (parent_commit && parent_version) {
                shwrap("""
                cosa meta \
                    --set fedora-coreos.parent-commit=${parent_commit} \
                    --set fedora-coreos.parent-version=${parent_version}
                """)
            }
        }

        def buildID = shwrapCapture("readlink builds/latest")
        if (prevBuildID == buildID) {
            currentBuild.result = 'SUCCESS'
            currentBuild.description = "${build_description} 💤 (no new build)"
            return
        }

        newBuildID = buildID
        currentBuild.description = "${build_description} ⚡ ${newBuildID}"


        pipeutils.tryWithMessagingCredentials() {
            shwrap("""
            /usr/lib/coreos-assembler/fedmsg-broadcast --fedmsg-conf=\${FEDORA_MESSAGING_CONF} \
                build.state.change --build ${newBuildID} --basearch ${basearch} --stream ${params.STREAM} \
                --build-dir ${BUILDS_BASE_HTTP_URL}/${params.STREAM}/builds/${newBuildID}/${basearch} \
                --state STARTED
            """)
        }

        if (uploading) {
            pipeutils.tryWithMessagingCredentials() {
                stage('Sign OSTree') {
                    pipeutils.shwrapWithAWSBuildUploadCredentials("""
                    cosa sign --build=${newBuildID} --arch=${basearch} \
                        robosignatory --s3 ${s3_stream_dir}/builds \
                        --aws-config-file \${AWS_BUILD_UPLOAD_CONFIG} \
                        --extra-fedmsg-keys stream=${params.STREAM} \
                        --ostree --gpgkeypath /etc/pki/rpm-gpg \
                        --fedmsg-conf=\${FEDORA_MESSAGING_CONF}
                    """)
                }
            }
        }

        // A few independent tasks that can be run in parallel
        def parallelruns = [:]

        // Generate KeyLime hashes for attestation on builds
        // This is a POC setup and will be modified over time
        // See: https://github.com/keylime/enhancements/blob/master/16_remote_allowlist_retrieval.md
        parallelruns['KeyLime Hash Generation'] = {
            shwrap("""
            cosa generate-hashlist --arch=${basearch} --release=${newBuildID} \
                --output=builds/${newBuildID}/${basearch}/exp-hash.json
            source="builds/${newBuildID}/${basearch}/exp-hash.json"
            target="builds/${newBuildID}/${basearch}/exp-hash.json-CHECKSUM"
            cosa shell -- bash -c "sha256sum \$source > \$target"
            """)
        }

        // Build QEMU image
        parallelruns['Build QEMU'] = {
            shwrap("cosa buildextend-qemu")
        }

        // process this batch
        parallel parallelruns

        // This is a temporary hack to help debug https://github.com/coreos/fedora-coreos-tracker/issues/1108.
        if (params.KOLA_RUN_SLEEP) {
            echo "Hit KOLA_RUN_SLEEP; going to sleep..."
            shwrap("sleep infinity")
            throw new Exception("unreachable")
        }

        // Run Kola Tests
        stage("Kola") {
            def n = ncpus - 1 // remove 1 for upgrade test
            kola(cosaDir: env.WORKSPACE, parallel: n, arch: basearch,
                 skipUpgrade: pipecfg.hacks?.skip_upgrade_tests,
                 allowUpgradeFail: params.ALLOW_KOLA_UPGRADE_FAILURE,
                 skipSecureBoot: pipecfg.hotfix?.skip_secureboot_tests_hack)
        }

        // Define a closure for what to do when we want to fork off
        // the multi-arch builds.
        archive_ostree_and_fork_mArch_jobs = {
            // If we are uploading results then let's do an early archive
            // of just the OSTree. This has the desired side effect of
            // reserving our build ID before we fork off multi-arch builds.
            stage('Archive OSTree') {
                if (uploading) {
                    // run with --force here in case the previous run of the
                    // pipeline died in between buildupload and bump_builds_json()
                    pipeutils.shwrapWithAWSBuildUploadCredentials("""
                    cosa buildupload --force --skip-builds-json --artifact=ostree \
                        s3 --aws-config-file=\${AWS_BUILD_UPLOAD_CONFIG} \
                        --acl=public-read ${s3_stream_dir}/builds
                    """)
                    pipeutils.bump_builds_json(
                        params.STREAM,
                        newBuildID,
                        basearch,
                        s3_stream_dir)
                }
            }

            stage('Fork Multi-Arch Builds') {
                if (uploading) {
                    for (arch in additional_arches) {
                        // We pass in FORCE=true here since if we got this far we know
                        // we want to do a build even if the code tells us that there
                        // are no apparent changes since the previous commit.
                        build job: 'build-arch', wait: false, parameters: [
                            booleanParam(name: 'FORCE', value: true),
                            booleanParam(name: 'ALLOW_KOLA_UPGRADE_FAILURE', value: params.ALLOW_KOLA_UPGRADE_FAILURE),
                            string(name: 'SRC_CONFIG_COMMIT', value: src_config_commit),
                            string(name: 'COREOS_ASSEMBLER_IMAGE', value: cosa_img),
                            string(name: 'STREAM', value: params.STREAM),
                            string(name: 'VERSION', value: newBuildID),
                            string(name: 'ARCH', value: arch),
                            string(name: 'PIPECFG_HOTFIX_REPO', value: params.PIPECFG_HOTFIX_REPO),
                            string(name: 'PIPECFG_HOTFIX_REF', value: params.PIPECFG_HOTFIX_REF)
                        ]
                    }
                }
            }
        }

        // If desired let's go ahead and archive+fork the multi-arch jobs
        if (params.EARLY_ARCH_JOBS) {
            archive_ostree_and_fork_mArch_jobs.call()
        }

        // Build the remaining artifacts
        stage("Build Artifacts") {
            pipeutils.build_artifacts(pipecfg, params.STREAM, basearch)
        }

        // Run Kola TestISO tests for metal artifacts
        if (shwrapCapture("cosa meta --get-value images.live-iso") != "None") {
            if (pipecfg.hacks?.skip_uefi_tests_on_older_rhcos &&
                (params.STREAM in ['4.6', '4.7', '4.8', '4.9'])) {
                // UEFI tests on x86_64 seem to fail on older RHCOS. skip UEFI tests here.
                stage("Kola:TestISO") {
                    kolaTestIso(cosaDir: env.WORKSPACE, arch: basearch,
                                skipUEFI: true,
                                skipSecureBoot: pipecfg.hotfix?.skip_secureboot_tests_hack)
                }
            } else {
                stage("Kola:TestISO") {
                    kolaTestIso(cosaDir: env.WORKSPACE, arch: basearch,
                                skipSecureBoot: pipecfg.hotfix?.skip_secureboot_tests_hack)
                    // For now we want to notify ourselves when a particular workaround is observed.
                    // It won't fail the build, just give us information.
                    // https://github.com/coreos/fedora-coreos-tracker/issues/1233
                    // XXX: This relies on implementation details in kolatestIso(),
                    //      but since this is a hack and probably short lived that's OK.
                    // First check to make sure the files exist, then grep for the workaround.
                    shwrap("cosa shell -- ls tmp/kolaTestIso-*/kola-testiso-uefi/insecure/{iso-live-login,iso-as-disk}/console.txt")
                    def grepRc = shwrapRc("""
                         cosa shell -- grep 'tracker issue workaround engaged for .*issues/1233' \
                            tmp/kolaTestIso-*/kola-testiso-uefi/insecure/{iso-live-login,iso-as-disk}/console.txt
                    """)
                    if (grepRc == 0) {
                        warnError(message: 'Detected used workaround for #1233') {
                            error('Detected used workaround for #1233')
                        }
                    }
                }
            }
        }

        // Upload to relevant clouds
        // XXX: we don't support cloud uploads yet for hotfixes
        if (uploading && !pipecfg.hotfix) {
            stage('Cloud Upload') {
                libcloud.upload_to_clouds(pipecfg, basearch, newBuildID, params.STREAM)
            }
        }

        // If we didn't do an early archive and start multi-arch
        // jobs let's go ahead and do those pieces now
        if (!params.EARLY_ARCH_JOBS) {
            archive_ostree_and_fork_mArch_jobs.call()
        }

        stage('Archive') {
            // lower to make sure we don't go over and account for overhead
            pipeutils.withXzMemLimit(cosa_memory_request_mb - 256) {
                def format = pipecfg.hacks?.override_compression_format
                format = format ?: 'xz' // Default to xz
                shwrap("cosa compress --compressor ${format}")
            }

            if (uploading) {
                // just upload as public-read for now, but see discussions in
                // https://github.com/coreos/fedora-coreos-tracker/issues/189
                pipeutils.shwrapWithAWSBuildUploadCredentials("""
                cosa buildupload --skip-builds-json s3 \
                    --aws-config-file \${AWS_BUILD_UPLOAD_CONFIG} \
                    --acl=public-read ${s3_stream_dir}/builds
                """)
            }
        }

        // These steps interact with Fedora Infrastructure/Releng for
        // signing of artifacts and importing of OSTree commits. They
        // must be run after the archive stage because the artifacts
        // are pulled from their S3 locations. They can be run in
        // parallel.
        if (uploading) {
            pipeutils.tryWithMessagingCredentials() {
                parallelruns = [:]
                parallelruns['Sign Images'] = {
                    pipeutils.shwrapWithAWSBuildUploadCredentials("""
                    cosa sign --build=${newBuildID} --arch=${basearch} \
                        robosignatory --s3 ${s3_stream_dir}/builds \
                        --aws-config-file \${AWS_BUILD_UPLOAD_CONFIG} \
                        --extra-fedmsg-keys stream=${params.STREAM} \
                        --images --gpgkeypath /etc/pki/rpm-gpg \
                        --fedmsg-conf \${FEDORA_MESSAGING_CONF}
                    """)
                }
                parallelruns['OSTree Import: Compose Repo'] = {
                    shwrap("""
                    cosa shell -- \
                    /usr/lib/coreos-assembler/fedmsg-send-ostree-import-request \
                        --build=${newBuildID} --arch=${basearch} \
                        --s3=${s3_stream_dir} --repo=compose \
                        --fedmsg-conf \${FEDORA_MESSAGING_CONF}
                    """)
                }
                // process this batch
                parallel parallelruns
            }
        }

        // Now that the metadata is uploaded go ahead and kick off some followup tests.
        if (uploading) {
            stage('Cloud Tests') {
                pipeutils.run_cloud_tests(pipecfg, params.STREAM, newBuildID,
                                          s3_stream_dir, basearch, src_config_commit)
            }
        }

        // For now, we auto-release all non-production streams builds. That
        // way, we can e.g. test testing-devel AMIs easily.
        //
        // Since we are only running this stage for non-production (i.e. mechanical
        // and development) builds we'll default to not doing AWS AMI replication.
        // We'll also default to allowing failures for additonal architectures.
        if (uploading && stream_info.type != "production") {
            stage('Publish') {
                build job: 'release', wait: false, parameters: [
                    string(name: 'STREAM', value: params.STREAM),
                    string(name: 'ADDITIONAL_ARCHES', value: additional_arches.join(" ")),
                    string(name: 'VERSION', value: newBuildID),
                    booleanParam(name: 'ALLOW_MISSING_ARCHES', value: true),
                    booleanParam(name: 'CLOUD_REPLICATION', value: params.CLOUD_REPLICATION),
                    string(name: 'PIPECFG_HOTFIX_REPO', value: params.PIPECFG_HOTFIX_REPO),
                    string(name: 'PIPECFG_HOTFIX_REF', value: params.PIPECFG_HOTFIX_REF)
                ]
            }
        }

        currentBuild.result = 'SUCCESS'

} catch (e) {
    currentBuild.result = 'FAILURE'
    throw e
} finally {
    def color
    def message = "[${params.STREAM}][${basearch}] <${env.BUILD_URL}|${env.BUILD_NUMBER}>"

    if (currentBuild.result == 'SUCCESS') {
        if (!newBuildID) {
            // SUCCESS, but no new builds? Must've been a no-op
            return
        }
        message = ":sparkles: ${message} - SUCCESS"
        color = 'good';
    } else if (currentBuild.result == 'UNSTABLE') {
        message = ":warning: ${message} - WARNING"
        color = 'warning';
    } else {
        message = ":fire: ${message} - FAILURE"
        color = 'danger';
    }

    if (newBuildID) {
        message = "${message} (${newBuildID})"
    }

    echo message
    pipeutils.trySlackSend(color: color, message: message)
    pipeutils.tryWithMessagingCredentials() {
        shwrap("""
        /usr/lib/coreos-assembler/fedmsg-broadcast --fedmsg-conf=\${FEDORA_MESSAGING_CONF} \
            build.state.change --build ${newBuildID} --basearch ${basearch} --stream ${params.STREAM} \
            --build-dir ${BUILDS_BASE_HTTP_URL}/${params.STREAM}/builds/${newBuildID}/${basearch} \
            --state FINISHED --result ${currentBuild.result}
        """)
    }
}}}} // finally, cosaPod, timeout, and locks finish here
