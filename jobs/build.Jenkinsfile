import org.yaml.snakeyaml.Yaml;

node {
    checkout scm
    // these are script global vars
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
                          "Use 'none' to only build for x86_64. " +
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
      booleanParam(name: 'WAIT_FOR_RELEASE_JOB',
                   defaultValue: false,
                   description: 'Wait for the release job and propagate errors.'),
    ] + pipeutils.add_hotfix_parameters_if_supported()),
    buildDiscarder(logRotator(
        numToKeepStr: '100',
        artifactNumToKeepStr: '100'
    )),
    durabilityHint('PERFORMANCE_OPTIMIZED')
])

def build_description = "[${params.STREAM}][x86_64]"

// Reload pipecfg if a hotfix build was provided. The reason we do this here
// instead of loading the right one upfront is so that we don't modify the
// parameter definitions above and their default values.
if (params.PIPECFG_HOTFIX_REPO || params.PIPECFG_HOTFIX_REF) {
    node {
        pipecfg = pipeutils.load_pipecfg(params.PIPECFG_HOTFIX_REPO, params.PIPECFG_HOTFIX_REF)
        build_description = "[${params.STREAM}-${pipecfg.hotfix.name}][x86_64]"
    }
}

// runtime parameter always wins
def cosa_img = params.COREOS_ASSEMBLER_IMAGE
cosa_img = cosa_img ?: pipeutils.get_cosa_img(pipecfg, params.STREAM)
def additional_arches = []
if (params.ADDITIONAL_ARCHES != "none") {
    additional_arches = params.ADDITIONAL_ARCHES.split()
    additional_arches = additional_arches ?: pipeutils.get_additional_arches(pipecfg, params.STREAM)
}

def stream_info = pipecfg.streams[params.STREAM]

// If we are a mechanical stream then we can pin packages but we
// don't maintain complete lockfiles so we can't build in strict mode.
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

// matches between build/build-arch job
def timeout_mins = 240

if (params.WAIT_FOR_RELEASE_JOB) {
    // Waiting for the release job effectively means waiting for all the build-
    // arch jobs we trigger to finish. While we do overlap in execution (by
    // a lot when EARLY_ARCH_JOBS is set), let's just simplify and add its
    // timeout value to ours to account for this. Add 30 minutes more for the
    // release job itself.
    timeout_mins += timeout_mins + 30
}

lock(resource: "build-${params.STREAM}") {
    timeout(time: timeout_mins, unit: 'MINUTES') {
    cosaPod(cpu: "${ncpus}",
            memory: "${cosa_memory_request_mb}Mi",
            image: cosa_img,
            serviceAccount: "jenkins") {
    try {

        basearch = shwrapCapture("cosa basearch")
        currentBuild.description = "${build_description} Running"

        // this is defined IFF we *should* and we *can* upload to S3
        def s3_stream_dir

        if (pipecfg.s3 && pipeutils.AWSBuildUploadCredentialExists()) {
            s3_stream_dir = pipeutils.get_s3_streams_dir(pipecfg, params.STREAM)
        }

        // Now, determine if we should do any uploads to remote s3 buckets or clouds
        // Don't upload if the user told us not to or we're debugging with KOLA_RUN_SLEEP
        def uploading = false
        if (s3_stream_dir && (!params.NO_UPLOAD || params.KOLA_RUN_SLEEP)) {
            uploading = true
        }

        // add any additional root CA cert before we do anything that fetches
        pipeutils.addOptionalRootCA()

        def ref = pipeutils.get_source_config_ref_for_stream(pipecfg, params.STREAM)
        def src_config_commit = shwrapCapture("git ls-remote ${pipecfg.source_config.url} ${ref} | cut -d \$'\t' -f 1")

        stage('Init') {
            def yumrepos = pipecfg.source_config.yumrepos ? "--yumrepos ${pipecfg.source_config.yumrepos}" : ""
            def yumrepos_ref = pipecfg.source_config.yumrepos_ref ? "--yumrepos-branch ${pipecfg.source_config.yumrepos_ref}" : ""
            def variant = stream_info.variant ? "--variant ${stream_info.variant}" : ""
            shwrap("""
            cosa init --force --branch ${ref} --commit=${src_config_commit} ${yumrepos} ${yumrepos_ref} ${variant} ${pipecfg.source_config.url}
            """)

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

            // Nothing changed since the latest build. Check if it's missing
            // some arches and retrigger `build-arch` only for the missing
            // arches, and the follow-up `release` job. Match the exact src
            // config commit that was used. But only do this if there isn't
            // already outstanding work in progress for that build ID. Skip if
            // not uploading since it's required for multi-arch.
            if (uploading && !buildid_has_work_pending(buildID, additional_arches)) {
                def builds = readJSON file: "builds/builds.json"
                assert buildID == builds.builds[0].id
                def missing_arches = additional_arches - builds.builds[0].arches
                if (missing_arches) {
                    def meta = readJSON(text: shwrapCapture("cosa meta --build=${buildID} --dump"))
                    def rev = meta["coreos-assembler.config-gitrev"]
                    currentBuild.description = "${build_description} 🔨 ${buildID}"
                    // Run the mArch jobs and wait. We wait here because if they fail
                    // we don't want to bother running the release job again since the
                    // goal is to get a complete build.
                    run_multiarch_jobs(missing_arches, rev, buildID, cosa_img, true)
                    if (stream_info.type != "production") {
                        run_release_job(buildID)
                    }
                }
            }

            // And we're done!
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

        // Build QEMU image
        stage("Build QEMU") {
            shwrap("cosa buildextend-qemu")
        }

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

        // If desired let's go ahead and archive+fork the multi-arch jobs
        if (params.EARLY_ARCH_JOBS && uploading) {
            archive_ostree(newBuildID, basearch, s3_stream_dir)
            run_multiarch_jobs(additional_arches, src_config_commit, newBuildID, cosa_img, false)
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
        if (!params.EARLY_ARCH_JOBS && uploading) {
            archive_ostree(newBuildID, basearch, s3_stream_dir)
            run_multiarch_jobs(additional_arches, src_config_commit, newBuildID, cosa_img, false)
        }

        stage('Archive') {
            // lower to make sure we don't go over and account for overhead
            pipeutils.withXzMemLimit(cosa_memory_request_mb - 256) {
                shwrap("cosa compress")
            }

            if (uploading) {
                def acl = pipecfg.s3.acl ?: 'public-read'
                pipeutils.shwrapWithAWSBuildUploadCredentials("""
                cosa buildupload --skip-builds-json --arch=${basearch} s3 \
                    --aws-config-file \${AWS_BUILD_UPLOAD_CONFIG} \
                    --acl=${acl} ${s3_stream_dir}/builds
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
                def parallelruns = [:]
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
        if (uploading && stream_info.type != "production") {
            run_release_job(newBuildID)
        }

        currentBuild.result = 'SUCCESS'

} catch (e) {
    currentBuild.result = 'FAILURE'
    throw e
} finally {
    def color
    def stream = params.STREAM
    if (pipecfg.hotfix) {
        stream += "-${pipecfg.hotfix.name}"
    }
    def message = "[${stream}][${basearch}] <${env.BUILD_URL}|${env.BUILD_NUMBER}>"

    if (currentBuild.result == 'SUCCESS') {
        if (!newBuildID) {
            // SUCCESS, but no new builds? Must've been a no-op
            return
        }
        message = ":sparkles: ${message}"
    } else if (currentBuild.result == 'UNSTABLE') {
        message = ":warning: ${message}"
    } else {
        message = ":fire: ${message}"
    }

    if (newBuildID) {
        message = "${message} (${newBuildID})"
    }

    echo message
    pipeutils.trySlackSend(message: message)
    pipeutils.tryWithMessagingCredentials() {
        shwrap("""
        /usr/lib/coreos-assembler/fedmsg-broadcast --fedmsg-conf=\${FEDORA_MESSAGING_CONF} \
            build.state.change --build ${newBuildID} --basearch ${basearch} --stream ${params.STREAM} \
            --build-dir ${BUILDS_BASE_HTTP_URL}/${params.STREAM}/builds/${newBuildID}/${basearch} \
            --state FINISHED --result ${currentBuild.result}
        """)
    }
}}}} // finally, cosaPod, timeout, and locks finish here

// This does an early archive of just the OSTree. This has the desired side
// effect of reserving our build ID before we fork off multi-arch builds.
def archive_ostree(version, basearch, s3_stream_dir) {
    stage('Archive OSTree') {
        def acl = pipecfg.s3.acl ?: 'public-read'
        // run with --force here in case the previous run of the
        // pipeline died in between buildupload and bump_builds_json()
        pipeutils.shwrapWithAWSBuildUploadCredentials("""
        cosa buildupload --force --skip-builds-json --artifact=ostree \
            s3 --aws-config-file=\${AWS_BUILD_UPLOAD_CONFIG} \
            --acl=${acl} ${s3_stream_dir}/builds
        """)
        pipeutils.bump_builds_json(
            params.STREAM,
            version,
            basearch,
            s3_stream_dir,
            acl)
    }
}

def run_multiarch_jobs(arches, src_commit, version, cosa_img, wait) {
    stage('Fork Multi-Arch Builds') {
        parallel arches.collectEntries{arch -> [arch, {
            // We pass in FORCE=true here since if we got this far we know
            // we want to do a build even if the code tells us that there
            // are no apparent changes since the previous commit.
            build job: 'build-arch', wait: wait, parameters: [
                booleanParam(name: 'FORCE', value: true),
                booleanParam(name: 'ALLOW_KOLA_UPGRADE_FAILURE', value: params.ALLOW_KOLA_UPGRADE_FAILURE),
                string(name: 'SRC_CONFIG_COMMIT', value: src_commit),
                string(name: 'COREOS_ASSEMBLER_IMAGE', value: cosa_img),
                string(name: 'STREAM', value: params.STREAM),
                string(name: 'VERSION', value: version),
                string(name: 'ARCH', value: arch),
                string(name: 'PIPECFG_HOTFIX_REPO', value: params.PIPECFG_HOTFIX_REPO),
                string(name: 'PIPECFG_HOTFIX_REF', value: params.PIPECFG_HOTFIX_REF)
            ]
        }]}
    }
}

def run_release_job(buildID) {
    stage('Publish') {
        // Since we are only running this stage for non-production (i.e.
        // mechanical and development) builds we'll default to allowing failures
        // for additional architectures.
        build job: 'release', wait: params.WAIT_FOR_RELEASE_JOB, parameters: [
            string(name: 'STREAM', value: params.STREAM),
            string(name: 'ADDITIONAL_ARCHES', value: params.ADDITIONAL_ARCHES),
            string(name: 'VERSION', value: buildID),
            booleanParam(name: 'ALLOW_MISSING_ARCHES', value: true),
            booleanParam(name: 'CLOUD_REPLICATION', value: params.CLOUD_REPLICATION),
            string(name: 'PIPECFG_HOTFIX_REPO', value: params.PIPECFG_HOTFIX_REPO),
            string(name: 'PIPECFG_HOTFIX_REF', value: params.PIPECFG_HOTFIX_REF)
        ]
    }
}

def buildid_has_work_pending(buildID, arches) {
    def locked = true
    // these locks match the ones in the release job 
    def locks = arches.collect{[resource: "release-${buildID}-${it}"]}
    lock(resource: "release-${params.STREAM}", extra: locks, skipIfLocked: true) {
        // NB: `return` here wouldn't actually return from the function
        locked = false
    }
    return locked
}
