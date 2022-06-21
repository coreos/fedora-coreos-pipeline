import org.yaml.snakeyaml.Yaml;

def pipeutils, streams, official, uploading
def src_config_url, src_config_ref, s3_bucket, gcp_gs_bucket
node {
    checkout scm
    pipeutils = load("utils.groovy")
    streams = load("streams.groovy")
    pod = readFile(file: "manifests/pod.yaml")

    def pipecfg = pipeutils.load_config()
    src_config_url = pipecfg['source-config-url']
    src_config_ref = pipecfg['source-config-ref']
    s3_bucket = pipecfg['s3-bucket']
    gcp_gs_bucket = pipecfg['gcp-gs-bucket']

    official = pipeutils.isOfficial()
    if (official) {
        echo "Running in official (prod) mode."
    } else {
        echo "Running in unofficial pipeline on ${env.JENKINS_URL}."
    }
}

// Share with the Fedora testing account so we can test it afterwards
FEDORA_AWS_TESTING_USER_ID = "013116697141"

// Base URL through which to download artifacts
BUILDS_BASE_HTTP_URL = "https://builds.coreos.fedoraproject.org/prod/streams"


properties([
    pipelineTriggers([]),
    parameters([
      choice(name: 'STREAM',
             // list devel first so that it's the default choice
             choices: (streams.development + streams.production + streams.mechanical),
             description: 'Fedora CoreOS stream to build'),
      string(name: 'VERSION',
             description: 'Override default versioning mechanism',
             defaultValue: '',
             trim: true),
      string(name: 'ADDITIONAL_ARCHES',
             description: 'Space-separated list of additional target architectures',
             defaultValue: streams.additional_arches.join(" "),
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
    ]),
    buildDiscarder(logRotator(
        numToKeepStr: '100',
        artifactNumToKeepStr: '100'
    )),
    durabilityHint('PERFORMANCE_OPTIMIZED')
])

def is_mechanical = (params.STREAM in streams.mechanical)
// If we are a mechanical stream then we can pin packages but we
// don't maintin complete lockfiles so we can't build in strict mode.
def strict_build_param = is_mechanical ? "" : "--strict"

// Note the supermin VM just uses 2G. The really hungry part is xz, which
// without lots of memory takes lots of time. For now we just hardcode these
// here; we can look into making them configurable through the template if
// developers really need to tweak them (note that in the default minimal devel
// workflow, only the qemu image is built).
def cosa_memory_request_mb = 6.5 * 1024 as Integer

// substitute the right COSA image and mem request into the pod definition before spawning it
pod = pod.replace("COREOS_ASSEMBLER_MEMORY_REQUEST", "${cosa_memory_request_mb}Mi")
pod = pod.replace("COREOS_ASSEMBLER_IMAGE", params.COREOS_ASSEMBLER_IMAGE)

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


echo "Waiting for build-${params.STREAM} lock"
currentBuild.description = "[${params.STREAM}] Waiting"

lock(resource: "build-${params.STREAM}") {
    currentBuild.description = "[${params.STREAM}] Running"

    podTemplate(cloud: 'openshift', label: pod_label, yaml: pod) {
    node(pod_label) { container('coreos-assembler') {

        // print out details of the cosa image to help debugging
        shwrap("""
        cat /cosa/coreos-assembler-git.json
        """)

        // declare these early so we can use them in `finally` block
        def newBuildID
        def basearch = shwrapCapture("cosa basearch")

        try { timeout(time: 240, unit: 'MINUTES') {

        // Clone the automation repo, which contains helper scripts. In the
        // future, we'll probably want this either part of the cosa image, or
        // in a derivative of cosa for pipeline needs.
        shwrap("""
        git clone --depth=1 https://github.com/coreos/fedora-coreos-releng-automation /var/tmp/fcos-releng
        """)

        // this is defined IFF we *should* and we *can* upload to S3
        def s3_stream_dir

        if (s3_bucket && utils.pathExists("\${AWS_FCOS_BUILDS_BOT_CONFIG}")) {
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

        def local_builddir = "/srv/devel/streams/${params.STREAM}"
        def fcos_config_commit

        stage('Init') {

            def ref = params.STREAM
            if (src_config_ref != "") {
                ref = src_config_ref
            }

            // for now, just use the PVC to keep cache.qcow2 in a stream-specific dir
            def cache_img = "/srv/prod/${params.STREAM}/cache.qcow2"

            shwrap("""
            cosa init --force --branch ${ref} ${src_config_url}
            mkdir -p \$(dirname ${cache_img})
            ln -s ${cache_img} cache/cache.qcow2
            """)

            // Capture the exact git commit used. Will pass to multi-arch pipeline runs.
            fcos_config_commit = shwrapCapture("git -C src/config rev-parse HEAD")

            // If the cache img is larger than 7G, then nuke it. Otherwise
            // it'll just keep growing and we'll hit ENOSPC. It'll get rebuilt.
            shwrap("""
            if [ -f ${cache_img} ] && [ \$(du ${cache_img} | cut -f1) -gt \$((1024*1024*7)) ]; then
                rm -vf ${cache_img}
            fi
            """)
        }

        def parent_version = ""
        def parent_commit = ""
        stage('Fetch') {
            if (s3_stream_dir) {
                pipeutils.aws_s3_cp_allow_noent("s3://${s3_stream_dir}/releases.json", "tmp/releases.json")
                if (utils.pathExists("tmp/releases.json")) {
                    def releases = readJSON file: "tmp/releases.json"
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

                shwrap("""
                export AWS_CONFIG_FILE=\${AWS_FCOS_BUILDS_BOT_CONFIG}
                cosa buildfetch --url s3://${s3_stream_dir}/builds
                """)
                if (parent_version != "") {
                    // also fetch the parent version; this is used by cosa to do the diff
                    shwrap("""
                    export AWS_CONFIG_FILE=\${AWS_FCOS_BUILDS_BOT_CONFIG}
                    cosa buildfetch --url s3://${s3_stream_dir}/builds --build ${parent_version}
                    """)
                }
            } else if (utils.pathExists(local_builddir)) {
                shwrap("""
                cosa buildfetch --url ${local_builddir}
                """)
            }

            shwrap("""
            cosa fetch ${strict_build_param}
            """)
        }

        def prevBuildID = null
        if (utils.pathExists("builds/latest")) {
            prevBuildID = shwrapCapture("readlink builds/latest")
        }

        stage('Build') {
            def parent_arg = ""
            if (parent_version != "") {
                parent_arg = "--parent-build ${parent_version}"
            }

            def force = params.FORCE ? "--force" : ""
            def version = ""
            if (params.VERSION) {
                version = "--version ${params.VERSION}"
            } else if (official) {
                // only use versioning that matches prod if we are running in the
                // official pipeline.
                def new_version = shwrapCapture("/var/tmp/fcos-releng/scripts/versionary.py")
                version = "--version ${new_version}"
            }
            shwrap("""
            cosa build ostree ${strict_build_param} --skip-prune ${force} ${version} ${parent_arg}
            """)
        }

        def meta_json
        def buildID = shwrapCapture("readlink builds/latest")
        if (prevBuildID == buildID) {
            currentBuild.result = 'SUCCESS'
            currentBuild.description = "[${params.STREAM}] 💤 (no new build)"
            return
        } else {
            newBuildID = buildID
            currentBuild.description = "[${params.STREAM}] ⚡ ${newBuildID}"
            meta_json = "builds/${newBuildID}/${basearch}/meta.json"

            // and insert the parent info into meta.json so we can display it in
            // the release browser and for sanity checking
            if (parent_commit && parent_version) {
                def meta = readJSON file: meta_json
                meta["fedora-coreos.parent-version"] = parent_version
                meta["fedora-coreos.parent-commit"] = parent_commit
                writeJSON file: meta_json, json: meta
            }

            if (official) {
                shwrap("""
                /var/tmp/fcos-releng/scripts/broadcast-fedmsg.py --fedmsg-conf=/etc/fedora-messaging-cfg/fedmsg.toml \
                    build.state.change --build ${newBuildID} --basearch ${basearch} --stream ${params.STREAM} \
                    --build-dir ${BUILDS_BASE_HTTP_URL}/${params.STREAM}/builds/${newBuildID}/${basearch} \
                    --state STARTED
                """)
            }
        }

        if (official && uploading && utils.pathExists("/etc/fedora-messaging-cfg/fedmsg.toml")) {
            stage('Sign OSTree') {
                shwrap("""
                export AWS_CONFIG_FILE=\${AWS_FCOS_BUILDS_BOT_CONFIG}
                cosa sign --build=${newBuildID} --arch=${basearch} \
                    robosignatory --s3 ${s3_stream_dir}/builds \
                    --extra-fedmsg-keys stream=${params.STREAM} \
                    --ostree --gpgkeypath /etc/pki/rpm-gpg \
                    --fedmsg-conf /etc/fedora-messaging-cfg/fedmsg.toml
                """)
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
            sha256sum builds/${newBuildID}/${basearch}/exp-hash.json \
                > builds/${newBuildID}/${basearch}/exp-hash.json-CHECKSUM
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
            tar -cf - tmp/kola/ | xz -c9 > kola-run-basic.tar.xz
            """)
            archiveArtifacts "kola-run-basic.tar.xz"
        }
        if (!pipeutils.checkKolaSuccess("tmp/kola")) {
            error('Kola:QEMU basic')
        }

        // reset for the next batch of independent tasks
        parallelruns = [:]

        // Kola QEMU tests
        parallelruns['Kola:QEMU'] = {
            // leave 512M for overhead & 1G for upgrade test; VMs are 1G each
            def parallel = ((cosa_memory_request_mb - 1536) / 1024) as Integer
            shwrap("""
            cosa kola run --rerun --parallel ${parallel} --no-test-exit-error
            tar -cf - tmp/kola/ | xz -c9 > kola-run.tar.xz
            """)
            archiveArtifacts "kola-run.tar.xz"
            if (!pipeutils.checkKolaSuccess("tmp/kola")) {
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
                tar -cf - tmp/kola-upgrade | xz -c9 > kola-run-upgrade.tar.xz
                """)
                archiveArtifacts "kola-run-upgrade.tar.xz"
                if (!pipeutils.checkKolaSuccess("tmp/kola-upgrade")) {
                    error('Kola:QEMU Upgrade')
                }
            } catch(e) {
                if (params.ALLOW_KOLA_UPGRADE_FAILURE) {
                    warnError(message: 'Upgrade Failed') {
                        error(e)
                    }
                } else {
                    throw e
                }
            }
        }

        // process this batch
        parallel parallelruns

        // If we are uploading results then let's do an early archive
        // of just the OSTree. This has the desired side effect of
        // reserving our build ID before we fork off multi-arch builds.
        stage('Archive OSTree') {
            if (uploading) {
              // run with --force here in case the previous run of the
              // pipeline died in between buildupload and bump_builds_json()
              shwrap("""
              export AWS_CONFIG_FILE=\${AWS_FCOS_BUILDS_BOT_CONFIG}
              cosa buildupload --force --skip-builds-json --artifact=ostree \
                  s3 --acl=public-read ${s3_stream_dir}/builds
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
                for (arch in params.ADDITIONAL_ARCHES.split()) {
                    build job: 'build-arch', wait: false, parameters: [
                        booleanParam(name: 'FORCE', value: params.FORCE),
                        booleanParam(name: 'MINIMAL', value: params.MINIMAL),
                        string(name: 'FCOS_CONFIG_COMMIT', value: fcos_config_commit),
                        string(name: 'COREOS_ASSEMBLER_IMAGE', value: params.COREOS_ASSEMBLER_IMAGE),
                        string(name: 'STREAM', value: params.STREAM),
                        string(name: 'VERSION', value: newBuildID),
                        string(name: 'ARCH', value: arch)
                    ]
                }
            }
        }

        if (!params.MINIMAL) {

            // We will parallel build all the different artifacts for this architecture. We split this up
            // into two separate parallel runs to stay in the sweet spot and avoid hitting PID limits in
            // our pipeline environment.
            //
            // First, define a list of all the derivative artifacts for this architecture.
            def artifacts = ["Aliyun", "AWS", "Azure", "AzureStack", "DigitalOcean", "Exoscale",
                             "GCP", "IBMCloud", "Nutanix", "OpenStack", "VirtualBox", "VMware", "Vultr"]
            // For all architectures we need to build the metal/metal4k artifacts and the Live ISO. Since the
            // ISO depends on the Metal/Metal4k images we'll make sure to put the Metal* ones in the first run
            // and the Live ISO in the second run.
            artifacts.add(0, "Metal")
            artifacts.add(1, "Metal4k")
            artifacts.add("Live")
            // Run the two runs of parallel builds
            parallelruns = artifacts.collectEntries {
                [it, {
                    def cmd = it.toLowerCase()
                    shwrap("""
                    cosa buildextend-${cmd}
                    """)
                }]
            }
            def artifacts_split_idx = artifacts.size().intdiv(2)
            parallel parallelruns.subMap(artifacts[0..artifacts_split_idx-1])
            parallel parallelruns.subMap(artifacts[artifacts_split_idx..-1])

            stage('Test Live ISO') {
                // compress the metal and metal4k images now so we're testing
                // installs with the image format we ship
                // lower to make sure we don't go over and account for overhead
                def xz_memlimit = cosa_memory_request_mb - 512
                shwrap("""
                export XZ_DEFAULTS=--memlimit=${xz_memlimit}Mi
                cosa compress --compressor xz --artifact metal --artifact metal4k
                """)
                try {
                    parallel metal: {
                        shwrap("kola testiso -S --output-dir tmp/kola-metal")
                    }, metal4k: {
                        shwrap("kola testiso -SP --qemu-native-4k --output-dir tmp/kola-metal4k")
                    }, uefi: {
                        shwrap("mkdir -p tmp/kola-uefi")
                        shwrap("kola testiso -S --qemu-firmware=uefi --scenarios iso-live-login,iso-as-disk --output-dir tmp/kola-uefi/insecure")
                        shwrap("kola testiso -S --qemu-firmware=uefi-secure --scenarios iso-live-login,iso-as-disk --output-dir tmp/kola-uefi/secure")
                    }
                } catch (Throwable e) {
                    throw e
                } finally {
                    shwrap("tar -cf - tmp/kola-metal/ | xz -c9 > ${env.WORKSPACE}/kola-testiso-metal.tar.xz")
                    shwrap("tar -cf - tmp/kola-metal4k/ | xz -c9 > ${env.WORKSPACE}/kola-testiso-metal4k.tar.xz")
                    shwrap("tar -cf - tmp/kola-uefi/ | xz -c9 > ${env.WORKSPACE}/kola-testiso-uefi.tar.xz")
                    archiveArtifacts allowEmptyArchive: true, artifacts: 'kola-testiso*.tar.xz'
                }
            }


            // reset for the next batch of independent tasks
            parallelruns = [:]

            // Key off of uploading: i.e. if we're configured to upload artifacts
            // to S3, we also take that to mean we should upload an AMI. We could
            // split this into two separate developer knobs in the future.
            if (uploading && !is_mechanical) {
                parallelruns['Upload AWS'] = {
                    // XXX: hardcode us-east-1 for now
                    // XXX: use the temporary 'ami-import' subpath for now; once we
                    // also publish vmdks, we could make this more efficient by
                    // uploading first, and then pointing ore at our uploaded vmdk
                    shwrap("""
                    export AWS_CONFIG_FILE=\${AWS_FCOS_BUILDS_BOT_CONFIG}
                    cosa buildextend-aws \
                        --upload \
                        --build=${newBuildID} \
                        --region=us-east-1 \
                        --bucket s3://${s3_bucket}/ami-import \
                        --grant-user ${FEDORA_AWS_TESTING_USER_ID}
                    """)
                }
            }

            // If there is a config for GCP then we'll upload our image to GCP
            if (uploading && !is_mechanical && utils.pathExists("\${GCP_IMAGE_UPLOAD_CONFIG}")) {
                parallelruns['Upload GCP'] = {
                    shwrap("""
                    # pick up the project to use from the config
                    gcp_project=\$(jq -r .project_id \${GCP_IMAGE_UPLOAD_CONFIG})
                    # collect today's date for the description
                    today=\$(date +%Y-%m-%d)
                    # NOTE: Add --deprecated to create image in deprecated state.
                    #       We undeprecate in the release pipeline with promote-image.
                    cosa buildextend-gcp \
                        --log-level=INFO \
                        --build=${newBuildID} \
                        --upload \
                        --create-image=true \
                        --deprecated \
                        --family fedora-coreos-${params.STREAM} \
                        --license fedora-coreos-${params.STREAM} \
                        --license "https://compute.googleapis.com/compute/v1/projects/vm-options/global/licenses/enable-vmx" \
                        --project=\${gcp_project} \
                        --bucket gs://${gcp_gs_bucket}/image-import \
                        --json \${GCP_IMAGE_UPLOAD_CONFIG} \
                        --description=\"Fedora, Fedora CoreOS ${params.STREAM}, ${newBuildID}, ${basearch} published on \$today\"
                    """)
                }
            }

            // process this batch
            parallel parallelruns
        }

        stage('Archive') {
            // lower to make sure we don't go over and account for overhead
            def xz_memlimit = cosa_memory_request_mb - 512
            shwrap("""
            export XZ_DEFAULTS=--memlimit=${xz_memlimit}Mi
            cosa compress --compressor xz
            """)

            if (uploading) {
              // just upload as public-read for now, but see discussions in
              // https://github.com/coreos/fedora-coreos-tracker/issues/189
              shwrap("""
              export AWS_CONFIG_FILE=\${AWS_FCOS_BUILDS_BOT_CONFIG}
              cosa buildupload --skip-builds-json \
                  s3 --acl=public-read ${s3_stream_dir}/builds
              """)
            } else {
              // Without an S3 server, just archive into the PVC
              // itself. Otherwise there'd be no other way to retrieve the
              // artifacts. But note we only keep one build at a time.
              shwrap("""
              rm -rf ${local_builddir}
              mkdir -p ${local_builddir}
              cp -aT builds ${local_builddir}
              """)
            }
        }

        // These steps interact with Fedora Infrastructure/Releng for
        // signing of artifacts and importing of OSTree commits. They
        // must be run after the archive stage because the artifacts
        // are pulled from their S3 locations.
        if (official && uploading && utils.pathExists("/etc/fedora-messaging-cfg/fedmsg.toml")) {
            stage('Sign Images') {
                shwrap("""
                export AWS_CONFIG_FILE=\${AWS_FCOS_BUILDS_BOT_CONFIG}
                cosa sign --build=${newBuildID} --arch=${basearch} \
                    robosignatory --s3 ${s3_stream_dir}/builds \
                    --extra-fedmsg-keys stream=${params.STREAM} \
                    --images --gpgkeypath /etc/pki/rpm-gpg \
                    --fedmsg-conf /etc/fedora-messaging-cfg/fedmsg.toml
                """)
            }
            stage("OSTree Import: Compose Repo") {
                shwrap("""
                /var/tmp/fcos-releng/coreos-ostree-importer/send-ostree-import-request.py \
                    --build=${newBuildID} --s3=${s3_stream_dir} --repo=compose \
                    --fedmsg-conf=/etc/fedora-messaging-cfg/fedmsg.toml
                """)
            }
        }

        // Now that the metadata is uploaded go ahead and kick off some tests
        // These can all be kicked off in parallel. These take little time
        // so there isn't much benefit in running them in parallel, but it
        // makes the UI view have less columns, which is useful.
        parallelruns = [:]

        if (!params.MINIMAL && uploading &&
                utils.pathExists("\${AWS_FCOS_KOLA_BOT_CONFIG}") && !is_mechanical) {
            parallelruns['Kola:AWS'] = {
                // We consider the AWS kola tests to be a followup job, so we use `wait: false` here.
                build job: 'kola-aws', wait: false, parameters: [
                    string(name: 'STREAM', value: params.STREAM),
                    string(name: 'VERSION', value: newBuildID),
                    string(name: 'S3_STREAM_DIR', value: s3_stream_dir),
                    string(name: 'FCOS_CONFIG_COMMIT', value: fcos_config_commit)
                ]
            }
          // XXX: This is failing right now. Disable until the New
          // Year when someone can dig into the problem.
          //parallelruns['Kola:Kubernetes'] = {
          //    // We consider the Kubernetes kola tests to be a followup job, so we use `wait: false` here.
          //    build job: 'kola-kubernetes', wait: false, parameters: [
          //        string(name: 'STREAM', value: params.STREAM),
          //        string(name: 'VERSION', value: newBuildID),
          //        string(name: 'S3_STREAM_DIR', value: s3_stream_dir),
          //        string(name: 'FCOS_CONFIG_COMMIT', value: fcos_config_commit)
          //    ]
          //}
        }
        if (!params.MINIMAL && uploading &&
                utils.pathExists("\${AZURE_KOLA_TESTS_CONFIG}") && !is_mechanical) {
            parallelruns['Kola:Azure'] = {
                // We consider the Azure kola tests to be a followup job, so we use `wait: false` here.
                build job: 'kola-azure', wait: false, parameters: [
                    string(name: 'STREAM', value: params.STREAM),
                    string(name: 'VERSION', value: newBuildID),
                    string(name: 'S3_STREAM_DIR', value: s3_stream_dir),
                    string(name: 'FCOS_CONFIG_COMMIT', value: fcos_config_commit)
                ]
            }
        }
        if (!params.MINIMAL && uploading &&
                utils.pathExists("\${GCP_KOLA_TESTS_CONFIG}") && !is_mechanical) {
            parallelruns['Kola:GCP'] = {
                // We consider the GCP kola tests to be a followup job, so we use `wait: false` here.
                build job: 'kola-gcp', wait: false, parameters: [
                    string(name: 'STREAM', value: params.STREAM),
                    string(name: 'VERSION', value: newBuildID),
                    string(name: 'S3_STREAM_DIR', value: s3_stream_dir),
                    string(name: 'FCOS_CONFIG_COMMIT', value: fcos_config_commit)
                ]
            }
        }
        if (!params.MINIMAL && uploading &&
                utils.pathExists("\${OPENSTACK_KOLA_TESTS_CONFIG}") && !is_mechanical) {
            parallelruns['Kola:OpenStack'] = {
                // We consider the OpenStack kola tests to be a followup job, so we use `wait: false` here.
                build job: 'kola-openstack', wait: false, parameters: [
                    string(name: 'STREAM', value: params.STREAM),
                    string(name: 'VERSION', value: newBuildID),
                    string(name: 'S3_STREAM_DIR', value: s3_stream_dir),
                    string(name: 'FCOS_CONFIG_COMMIT', value: fcos_config_commit)
                ]
            }
        }

        // process this batch
        parallel parallelruns

        // For now, we auto-release all non-production streams builds. That
        // way, we can e.g. test testing-devel AMIs easily.
        //
        // Since we are only running this stage for non-production (i.e. mechanical
        // and development) builds we'll default to not doing AWS AMI replication.
        // We'll also default to allowing failures for additonal architectures.
        if (official && uploading && !(params.STREAM in streams.production)) {
            stage('Publish') {
                build job: 'release', wait: false, parameters: [
                    string(name: 'STREAM', value: params.STREAM),
                    string(name: 'ARCHES', value: basearch + " " + params.ADDITIONAL_ARCHES),
                    string(name: 'VERSION', value: newBuildID),
                    booleanParam(name: 'ALLOW_MISSING_ARCHES', value: true),
                    booleanParam(name: 'AWS_REPLICATION', value: params.AWS_REPLICATION)
                ]
            }
        }

        currentBuild.result = 'SUCCESS'

        // main timeout and try {} finish here
        }} catch (e) {
            currentBuild.result = 'FAILURE'
            throw e
        } finally {
            def color
            def message = "[${params.STREAM}] <${env.BUILD_URL}|${env.BUILD_NUMBER}>"

            if (currentBuild.result == 'SUCCESS') {
                if (!newBuildID) {
                    // SUCCESS, but no new builds? Must've been a no-op
                    return
                }
                message = ":fcos: :sparkles: ${message} - SUCCESS"
                color = 'good';
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
                shwrap("""
                /var/tmp/fcos-releng/scripts/broadcast-fedmsg.py --fedmsg-conf=/etc/fedora-messaging-cfg/fedmsg.toml \
                    build.state.change --build ${newBuildID} --basearch ${basearch} --stream ${params.STREAM} \
                    --build-dir ${BUILDS_BASE_HTTP_URL}/${params.STREAM}/builds/${newBuildID}/${basearch} \
                    --state FINISHED --result ${currentBuild.result}
                """)
            }
        }
    }}
}}
