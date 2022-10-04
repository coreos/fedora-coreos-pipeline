import org.yaml.snakeyaml.Yaml;

def pipeutils, pipecfg, official, uploading
def src_config_url, s3_bucket, aws_test_accounts
node {
    checkout scm
    pipeutils = load("utils.groovy")
    pipecfg = pipeutils.load_pipecfg()
    pod = readFile(file: "manifests/pod.yaml")


    def jenkinscfg = pipeutils.load_jenkins_config()
    src_config_url = pipecfg.source_config.url
    s3_bucket = pipecfg.s3_bucket

    // Extra AWS testing accounts to share images with
    aws_test_accounts = pipecfg.clouds?.aws?.test_accounts

    official = pipeutils.isOfficial()
    if (official) {
        echo "Running in official (prod) mode."
    } else {
        echo "Running in unofficial pipeline on ${env.JENKINS_URL}."
    }
}

// Base URL through which to download artifacts
BUILDS_BASE_HTTP_URL = "https://builds.coreos.fedoraproject.org/prod/streams"


properties([
    pipelineTriggers([]),
    parameters([
      choice(name: 'STREAM',
             choices: pipeutils.get_streams_choices(pipecfg),
             description: 'Fedora CoreOS stream to build'),
      string(name: 'VERSION',
             description: 'Build version',
             defaultValue: '',
             trim: true),
      string(name: 'ARCH',
             description: 'The target architecture',
             choices: pipecfg.additional_arches,
             trim: true),
      booleanParam(name: 'FORCE',
                   defaultValue: false,
                   description: 'Whether to force a rebuild'),
      booleanParam(name: 'MINIMAL',
                   defaultValue: (official ? false : true),
                   description: 'Whether to only build the OSTree and qemu images'),
      booleanParam(name: 'ALLOW_KOLA_UPGRADE_FAILURE',
                   defaultValue: false,
                   description: "Don't error out if upgrade tests fail (temporary)"),
      booleanParam(name: 'AWS_REPLICATION',
                   defaultValue: false,
                   description: 'Force AWS AMI replication for non-production'),
      string(name: 'COREOS_ASSEMBLER_IMAGE',
             description: 'Override coreos-assembler image to use',
             defaultValue: "coreos-assembler:main",
             trim: true),
      booleanParam(name: 'KOLA_RUN_SLEEP',
                   defaultValue: false,
                   description: 'Wait forever at kola tests stage. Implies NO_UPLOAD'),
      booleanParam(name: 'NO_UPLOAD',
                   defaultValue: false,
                   description: 'Do not upload results to S3; for debugging purposes.'),
      string(name: 'SRC_CONFIG_COMMIT',
             description: 'The exact config repo git commit to build against',
             defaultValue: '',
             trim: true),
    ]),
    buildDiscarder(logRotator(
        numToKeepStr: '100',
        artifactNumToKeepStr: '100'
    )),
    durabilityHint('PERFORMANCE_OPTIMIZED')
])

def stream_info = pipecfg.streams[params.STREAM]

// If we are a mechanical stream then we can pin packages but we
// don't maintin complete lockfiles so we can't build in strict mode.
def strict_build_param = stream_info.type == "mechanical" ? "" : "--strict"

// Note that the heavy lifting is done on a remote node via podman
// --remote so we shouldn't need much memory.
def cosa_memory_request_mb = 2.5 * 1024 as Integer

// the build-arch pod is mostly triggering the work on a remote node, so we
// can be conservative with our request
pod = pod.replace("COREOS_ASSEMBLER_CPU_REQUEST", "1")
pod = pod.replace("COREOS_ASSEMBLER_CPU_LIMIT", "1")

// We recently did some golang migration [1] and happened to add the
// `cosa remote-session` pieces [2] after that migration. This makes
// it not easy to backport to ther branches. For a period of time
// let's use the latest COSA for our COSA pod for multi-arch builds
// instead of the versioned COSA. We will still use the versioned COSA
// on the remote so the actual building will happened with the correctly
// versioned COSA (substituted below and passed as an argument to
// `cosa remote-session create --image=`).
// [1] https://github.com/coreos/coreos-assembler/pull/2919
// [2] https://github.com/coreos/coreos-assembler/pull/2979
def cosa_pod_image = params.COREOS_ASSEMBLER_IMAGE
if (cosa_pod_image =~ '^coreos-assembler:rhcos-4.(6|7|8|9|10|11)$') {
    cosa_pod_image = "coreos-assembler:main"
}

// substitute the right COSA image and mem request into the pod definition before spawning it
pod = pod.replace("COREOS_ASSEMBLER_MEMORY_REQUEST", "${cosa_memory_request_mb}Mi")
pod = pod.replace("COREOS_ASSEMBLER_IMAGE", cosa_pod_image)

def podYaml = readYaml(text: pod);

// And re-serialize; I couldn't figure out how to dump to a string
// in a way allowed by the Groovy sandbox.  Tempting to just tell people
// to disable that.
node {
    def tmpPath = "${WORKSPACE}/pod.yaml";
    sh("rm -vf ${tmpPath}")
    writeYaml(file: tmpPath, data: podYaml);
    pod = readFile(file: tmpPath);
    sh("rm -vf ${tmpPath}")
}

echo "Final podspec: ${pod}"

// use a unique label to force Kubernetes to provision a separate pod per run
def pod_label = "cosa-${UUID.randomUUID().toString()}"


echo "Waiting for build-${params.STREAM}-${params.ARCH} lock"
currentBuild.description = "[${params.STREAM}][${params.ARCH}] Waiting"

// release lock: we want to block the release job until we're done.
// ideally we'd lock this from the main pipeline and have lock ownership
// transferred to us when we're triggered. in practice, it's very unlikely the
// release job would win this race.
lock(resource: "release-${params.VERSION}-${params.ARCH}") {
// build lock: we don't want multiple concurrent builds for the same stream and
// arch (though this should work fine in theory)
lock(resource: "build-${params.STREAM}-${params.ARCH}") {

    currentBuild.description = "[${params.STREAM}][${params.ARCH}] Running"

    podTemplate(cloud: 'openshift', label: pod_label, yaml: pod) {
    node(pod_label) { container('coreos-assembler') {

        // print out details of the cosa image to help debugging
        shwrap("""
        cat /cosa/coreos-assembler-git.json
        """)

        // declare these early so we can use them in `finally` block
        assert params.VERSION != ""
        def newBuildID = params.VERSION
        def basearch = params.ARCH

        // If we are using the image stream (the default) then just translate
        // that into a quay registry equivalent (the multi-arch builders can't
        // run containers from image streams).
        def image = params.COREOS_ASSEMBLER_IMAGE
        if (image.startsWith("coreos-assembler:")) {
            image = image.replaceAll("coreos-assembler:",
                                     "quay.io/coreos-assembler/coreos-assembler:")
        }

        try { timeout(time: 240, unit: 'MINUTES') {

        // Add in AWS Build Upload credentials here if they exist. In
        // the future we might choose to be more granular about when
        // we load this.
        pipeutils.tryWithOrWithoutCredentials([file(variable: 'AWS_BUILD_UPLOAD_CONFIG',
                                                    credentialsId: 'aws-build-upload-config')]) {

        // this is defined IFF we *should* and we *can* upload to S3
        def s3_stream_dir

        if (s3_bucket && utils.pathExists("\${AWS_BUILD_UPLOAD_CONFIG}")) {
            // see bucket layout in https://github.com/coreos/fedora-coreos-tracker/issues/189
            s3_stream_dir = "${s3_bucket}/prod/streams/${params.STREAM}"
        }

        // Now, determine if we should do any uploads to remote s3 buckets or clouds
        // Don't upload if the user told us not to or we're debugging with KOLA_RUN_SLEEP
        if (s3_stream_dir && (!params.NO_UPLOAD || params.KOLA_RUN_SLEEP)) {
            uploading = true
        } else {
            uploading = false
        }


        // Wrap a bunch of commands now inside the context of a remote
        // session. All `cosa` commands, other than `cosa remote-session`
        // commands, should get intercepted and executed on the remote.
        // We set environment variables that describe our remote host
        // that `podman --remote` will transparently pick up and use.
        // We set the session to time out after 4h. This essentially
        // performs garbage collection on the remote if we fail to clean up.
        pipeutils.withPodmanRemoteArchBuilder(arch: basearch) {
        def session = shwrapCapture("cosa remote-session create --image ${image} --expiration 4h")
        withEnv(["COREOS_ASSEMBLER_REMOTE_SESSION=${session}"]) {

        // add any additional root CA cert before we do anything that fetches
        pipeutils.addOptionalRootCA()

        def local_builddir = "/srv/devel/streams/${params.STREAM}"
        def ref = params.STREAM
        def src_config_commit
        if (params.SRC_CONFIG_COMMIT) {
            src_config_commit = params.SRC_CONFIG_COMMIT
        } else {
            src_config_commit = shwrapCapture("git ls-remote ${src_config_url} ${ref} | cut -d \$'\t' -f 1")
        }

        stage('Init') {
            def yumrepos = pipecfg.source_config.yumrepos ? "--yumrepos ${pipecfg.source_config.yumrepos}" : ""

            shwrap("""
            # sync over AWS secret if it exists
            if [ -f \${AWS_BUILD_UPLOAD_CONFIG} ]; then
                dir=\$(dirname \${AWS_BUILD_UPLOAD_CONFIG})
                cosa shell -- sudo install -d -D -o builder -g builder --mode 777 \${dir}
                cosa remote-session sync \${dir}/ :\${dir}/
            fi

            cosa init --force --branch ${ref} --commit=${src_config_commit} ${yumrepos} ${src_config_url}
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
                shwrap("""
                cosa buildfetch --arch=${basearch} \
                    --url s3://${s3_stream_dir}/builds \
                    --aws-config-file \${AWS_BUILD_UPLOAD_CONFIG}
                """)
                if (parent_version != "") {
                    // also fetch the parent version; this is used by cosa to do the diff
                    shwrap("""
                    cosa buildfetch --arch=${basearch} \
                        --build ${parent_version} \
                        --url s3://${s3_stream_dir}/builds \
                        --aws-config-file \${AWS_BUILD_UPLOAD_CONFIG}
                    """)
                }
            } else if (utils.pathExists(local_builddir)) {
                // if using local builddir then sync it from local and then
                // push to the remote
                shwrap("""
                COREOS_ASSEMBLER_REMOTE_SESSION= \
                    cosa buildfetch --url=${local_builddir} --arch=${basearch}
                cosa remote-session sync ./ :/srv/
                """)
            }
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
            def version = "--version ${params.VERSION}"
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

        currentBuild.description = "[${params.STREAM}][${basearch}] ⚡ ${newBuildID}"


        if (official) {
            pipeutils.tryWithMessagingCredentials() {
                shwrap("""
                /usr/lib/coreos-assembler/fedmsg-broadcast --fedmsg-conf=\${FEDORA_MESSAGING_CONF} \
                    build.state.change --build ${newBuildID} --basearch ${basearch} --stream ${params.STREAM} \
                    --build-dir ${BUILDS_BASE_HTTP_URL}/${params.STREAM}/builds/${newBuildID}/${basearch} \
                    --state STARTED
                """)
            }
        }

        if (official && uploading) {
            pipeutils.tryWithMessagingCredentials() {
                stage('Sign OSTree') {
                    shwrap("""
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

        stage('Kola:QEMU basic') {
            shwrap("""
            cosa kola run --rerun --basic-qemu-scenarios --no-test-exit-error
            cosa shell -- tar -c --xz tmp/kola/ > kola-run-basic.tar.xz
            cosa shell -- cat tmp/kola/reports/report.json > report.json
            """)
            archiveArtifacts "kola-run-basic.tar.xz"
            if (!pipeutils.checkKolaSuccess("report.json")) {
                error('Kola:QEMU basic')
            }
        }

        // reset for the next batch of independent tasks
        parallelruns = [:]

        // Kola QEMU tests
        parallelruns['Kola:QEMU'] = {
            shwrap("""
            cosa kola run --rerun --parallel 5 --no-test-exit-error --denylist-test basic  --tag '!reprovision'
            cosa shell -- tar -c --xz tmp/kola/ > kola-run.tar.xz
            cosa shell -- cat tmp/kola/reports/report.json > report.json
            """)
            archiveArtifacts "kola-run.tar.xz"
            if (!pipeutils.checkKolaSuccess("report.json")) {
                error('Kola:QEMU')
            }
            shwrap("""
            cosa shell -- rm -rf tmp/kola
            cosa kola run --rerun --no-test-exit-error --tag reprovision
            cosa shell -- tar -c --xz tmp/kola/ > kola-run-reprovision.tar.xz
            cosa shell -- cat tmp/kola/reports/report.json > report.json
            """)
            archiveArtifacts "kola-run-reprovision.tar.xz"
            if (!pipeutils.checkKolaSuccess("report.json")) {
                error('Kola:QEMU')
            }
        }

        // Kola QEMU Upgrade tests
        parallelruns['Kola:QEMU Upgrade'] = {
            // If upgrades are broken `cosa kola --upgrades` might
            // fail to even find the previous image so we wrap this
            // in a try/catch so ALLOW_KOLA_UPGRADE_FAILURE can work.
            try {
                shwrap("""
                cosa kola --rerun --upgrades --no-test-exit-error
                cosa shell -- tar -c --xz tmp/kola-upgrade/ > kola-run-upgrade.tar.xz
                cosa shell -- cat tmp/kola-upgrade/reports/report.json > report.json
                """)
                archiveArtifacts "kola-run-upgrade.tar.xz"
                if (!pipeutils.checkKolaSuccess("report.json")) {
                    error('Kola:QEMU Upgrade')
                }
            } catch(e) {
                if (params.ALLOW_KOLA_UPGRADE_FAILURE) {
                    warnError(message: 'Upgrade Failed') {
                        error(e.getMessage())
                    }
                } else {
                    throw e
                }
            }
        }

        // process this batch
        parallel parallelruns


        if (!params.MINIMAL) {


            // We will parallel build all the different artifacts for this architecture. We split this up
            // into two separate parallel runs to stay in the sweet spot and avoid hitting PID limits in
            // our pipeline environment.
            //
            // First, define a list of all the derivative artifacts for this architecture.
            def artifacts = pipeutils.get_artifacts_to_build(pipecfg, params.STREAM, basearch)
            // Sort the artifacts for two parallel runs
            artifacts = pipeutils.change_metal_artifacts_list_order(artifacts)
            // Run the two runs of parallel builds
            parallelruns = artifacts.collectEntries {
                [it, {
                    shwrap("""
                    cosa buildextend-${it}
                    """)
                }]
            }
            def artifacts_split_idx = artifacts.size().intdiv(2)
            parallel parallelruns.subMap(artifacts[0..artifacts_split_idx-1])
            parallel parallelruns.subMap(artifacts[artifacts_split_idx..-1])

            // Hack for serial console on aarch64 aws images
            // see https://github.com/coreos/fedora-coreos-tracker/issues/920#issuecomment-914334988
            // Right now we only patch if platforms.yaml hasn't made it to this stream yet.
            // Fold this back into the above parallel runs (i.e. add to config.yaml
            // artifacts list for aarch64 and delete below code and knob) once platforms.yaml
            // exists everywhere. https://github.com/coreos/fedora-coreos-config/pull/1181
            if (basearch == "aarch64") {
                stage('AWS') {
                    if (pipecfg.aws_aarch64_serial_console_hack) {
                        shwrap("""
                        if [ ! -e src/config/platforms.yaml ]; then
                            echo 'ZGlmZiAtLWdpdCBhL3NyYy9nZi1zZXQtcGxhdGZvcm0gYi9zcmMvZ2Ytc2V0LXBsYXRmb3JtCmluZGV4IDNiMWM1YWUzMS4uZGY1ZTBmOWQ3IDEwMDc1NQotLS0gYS9zcmMvZ2Ytc2V0LXBsYXRmb3JtCisrKyBiL3NyYy9nZi1zZXQtcGxhdGZvcm0KQEAgLTU5LDcgKzU5LDEzIEBAIGJsc2NmZ19wYXRoPSQoY29yZW9zX2dmIGdsb2ItZXhwYW5kIC9ib290L2xvYWRlci9lbnRyaWVzL29zdHJlZS0qLmNvbmYpCiBjb3Jlb3NfZ2YgZG93bmxvYWQgIiR7YmxzY2ZnX3BhdGh9IiAiJHt0bXBkfSIvYmxzLmNvbmYKICMgUmVtb3ZlIGFueSBwbGF0Zm9ybWlkIGN1cnJlbnRseSB0aGVyZQogc2VkIC1pIC1lICdzLCBpZ25pdGlvbi5wbGF0Zm9ybS5pZD1bYS16QS1aMC05XSosLGcnICIke3RtcGR9Ii9ibHMuY29uZgotc2VkIC1pIC1lICcvXm9wdGlvbnMgLyBzLCQsIGlnbml0aW9uLnBsYXRmb3JtLmlkPSciJHtwbGF0Zm9ybWlkfSInLCcgIiR7dG1wZH0iL2Jscy5jb25mCitpZiBbICIkKGNvcmVvc19nZiBleGlzdHMgL2Jvb3QvY29yZW9zL3BsYXRmb3Jtcy5qc29uKSIgIT0gInRydWUiIC1hICIke3BsYXRmb3JtaWR9IiA9PSAnYXdzJyBdOyB0aGVuCisgICAgIyBPdXIgcGxhdGZvcm0gaXMgQVdTIGFuZCB3ZSBzdGlsbCBuZWVkIHRoZSBjb25zb2xlPXR0eVMwIGhhY2sgZm9yIHRoZSBsZWdhY3kKKyAgICAjIChubyBwbGF0Zm9ybXMueWFtbCkgcGF0aC4KKyAgICBzZWQgLWkgLWUgJ3N8Xlwob3B0aW9ucyAuKlwpfFwxIGlnbml0aW9uLnBsYXRmb3JtLmlkPSciJHtwbGF0Zm9ybWlkfSInIGNvbnNvbGU9dHR5UzAsMTE1MjAwbjh8JyAiJHt0bXBkfSIvYmxzLmNvbmYKK2Vsc2UKKyAgICBzZWQgLWkgLWUgJy9eb3B0aW9ucyAvIHMsJCwgaWduaXRpb24ucGxhdGZvcm0uaWQ9JyIke3BsYXRmb3JtaWR9IicsJyAiJHt0bXBkfSIvYmxzLmNvbmYKK2ZpCiBpZiBbIC1uICIkcmVtb3ZlX2thcmdzIiBdOyB0aGVuCiAgICAgIyBSZW1vdmUgZXhpc3RpbmcgcWVtdS1zcGVjaWZpYyBrYXJncwogICAgIHNlZCAtaSAtZSAnL15vcHRpb25zIC8gc0AgJyIke3JlbW92ZV9rYXJnc30iJ0BAJyAiJHt0bXBkfSIvYmxzLmNvbmYKCg==' | base64 --decode | cosa shell -- sudo patch /usr/lib/coreos-assembler/gf-set-platform
                        fi
                        """)
                    }
                    shwrap("cosa buildextend-aws")
                }
            }

            stage('Test Live ISO') {
                // compress the metal and metal4k images now so we're testing
                // installs with the image format we ship
                shwrap("""
                cosa compress --compressor xz --artifact metal --artifact metal4k
                """)
                try {
                    parallelruns = [:]
                    parallelruns['metal'] = {
                        shwrap("cosa kola testiso -S --output-dir tmp/kola-testiso-metal")
                    }
                    // metal4k doesn't work on s390x right now: https://github.com/coreos/fedora-coreos-tracker/issues/1261
                    if (basearch != "s390x") {
                        parallelruns['metal4k'] = {
                            shwrap("cosa kola testiso -SP --qemu-native-4k --qemu-multipath --output-dir tmp/kola-testiso-metal4k")
                        }
                    }
                    // process this batch
                    parallel parallelruns
                } catch (Throwable e) {
                    throw e
                } finally {
                    shwrap("""
                    cosa shell -- tar -c --xz tmp/kola-testiso-metal/ > kola-testiso-metal.tar.xz
                    cosa shell -- tar -c --xz tmp/kola-testiso-metal4k/ > kola-testiso-metal4k.tar.xz || :
                    """)
                    archiveArtifacts allowEmptyArchive: true, artifacts: 'kola-testiso*.tar.xz'
                }
            }

            // reset for the next batch of independent tasks
            parallelruns = [:]

            // Key off of uploading: i.e. if we're configured to upload artifacts
            // to S3, we also take that to mean we should upload an AMI. We could
            // split this into two separate developer knobs in the future.
            if (basearch =="aarch64" && uploading) {
                parallelruns['Upload AWS'] = {
                    // XXX: hardcode us-east-1 for now
                    // XXX: use the temporary 'ami-import' subpath for now; once we
                    // also publish vmdks, we could make this more efficient by
                    // uploading first, and then pointing ore at our uploaded vmdk
                    def grant_user_args = aws_test_accounts.collect{"--grant-user ${it}"}.join(" ")
                    shwrap("""
                    cosa buildextend-aws \
                        --upload \
                        --arch=${basearch} \
                        --build=${newBuildID} \
                        --region=us-east-1 ${grant_user_args} \
                        --bucket s3://${s3_bucket}/ami-import \
                        --credentials-file=\${AWS_BUILD_UPLOAD_CONFIG}
                    """)
                }
            }

            // process this batch
            parallel parallelruns
        }

        stage('Archive') {
            shwrap("""
            cosa compress --compressor xz
            """)

            if (uploading) {
              // just upload as public-read for now, but see discussions in
              // https://github.com/coreos/fedora-coreos-tracker/issues/189
              shwrap("""
              cosa buildupload --skip-builds-json s3 \
                  --aws-config-file \${AWS_BUILD_UPLOAD_CONFIG} \
                  --acl=public-read ${s3_stream_dir}/builds
              """)
              pipeutils.bump_builds_json(
                  params.STREAM,
                  newBuildID,
                  basearch,
                  s3_stream_dir)
            } else {
              // Without an S3 server, just archive into the PVC
              // itself. Otherwise there'd be no other way to retrieve the
              // artifacts. But note we only keep one build at a time.
              shwrap("""
              rm -rf ${local_builddir}
              mkdir -p ${local_builddir}
              cosa remote-session sync :builds/ ${local_builddir}
              """)
            }
        }

        // These steps interact with Fedora Infrastructure/Releng for
        // signing of artifacts and importing of OSTree commits. They
        // must be run after the archive stage because the artifacts
        // are pulled from their S3 locations. They can be run in
        // parallel.
        if (official && uploading) {
            pipeutils.tryWithMessagingCredentials() {
                parallelruns = [:]
                parallelruns['Sign Images'] = {
                    shwrap("""
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

        stage('Destroy Remote') {
            shwrap("cosa remote-session destroy")
        }

        } // end withEnv
        } // end withPodmanRemoteArchBuilder

        // Now that the metadata is uploaded go ahead and kick off some tests
        // These can all be kicked off in parallel. These take little time
        // so there isn't much benefit in running them in parallel, but it
        // makes the UI view have less columns, which is useful.
        parallelruns = [:]

        if (basearch == "aarch64") {
            if (!params.MINIMAL && uploading) {
                // Kick off the Kola AWS job if we have credentials for running those tests.
                pipeutils.tryWithCredentials([file(variable: 'AWS_KOLA_TESTS_CONFIG',
                                                   credentialsId: 'aws-kola-tests-config')]) {
                    parallelruns['Kola:AWS'] = {
                        // We consider the AWS kola tests to be a followup job, so we use `wait: false` here.
                        build job: 'kola-aws', wait: false, parameters: [
                            string(name: 'STREAM', value: params.STREAM),
                            string(name: 'VERSION', value: newBuildID),
                            string(name: 'S3_STREAM_DIR', value: s3_stream_dir),
                            string(name: 'ARCH', value: basearch),
                            string(name: 'SRC_CONFIG_COMMIT', value: src_config_commit)
                        ]
                    }
                }
                // Kick off the Kola OpenStack job if we have credentials for running those tests.
                pipeutils.tryWithCredentials([file(variable: 'OPENSTACK_KOLA_TESTS_CONFIG',
                                              credentialsId: 'openstack-kola-tests-config')]) {
                    parallelruns['Kola:OpenStack'] = {
                        // We consider the OpenStack kola tests to be a followup job, so we use `wait: false` here.
                        build job: 'kola-openstack', wait: false, parameters: [
                            string(name: 'STREAM', value: params.STREAM),
                            string(name: 'VERSION', value: newBuildID),
                            string(name: 'S3_STREAM_DIR', value: s3_stream_dir),
                            string(name: 'ARCH', value: basearch),
                            string(name: 'SRC_CONFIG_COMMIT', value: src_config_commit)
                        ]
                    }
                }
            }
        }

        // process this batch
        parallel parallelruns


        currentBuild.result = 'SUCCESS'

        // main timeout and try {} and tryWithOrWithoutCredentials finish here
        }}} catch (e) {
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
                message = ":fcos: :sparkles: ${message} - SUCCESS"
                color = 'good';
            } else if (currentBuild.result == 'UNSTABLE') {
                message = ":fcos: :warning: ${message} - WARNING"
                color = 'warning';
            } else {
                message = ":fcos: :trashfire: ${message} - FAILURE"
                color = 'danger';
            }

            if (newBuildID) {
                message = "${message} (${newBuildID})"
            }

            echo message
            if (official) {
                slackSend(color: color, message: message)
            }
            if (official) {
                pipeutils.tryWithMessagingCredentials() {
                    shwrap("""
                    /usr/lib/coreos-assembler/fedmsg-broadcast --fedmsg-conf=\${FEDORA_MESSAGING_CONF} \
                        build.state.change --build ${newBuildID} --basearch ${basearch} --stream ${params.STREAM} \
                        --build-dir ${BUILDS_BASE_HTTP_URL}/${params.STREAM}/builds/${newBuildID}/${basearch} \
                        --state FINISHED --result ${currentBuild.result}
                    """)
                }
            }
        }
    }}
}}}
