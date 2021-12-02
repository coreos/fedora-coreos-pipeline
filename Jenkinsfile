import org.yaml.snakeyaml.Yaml;

def pipeutils, streams, official, official_jenkins, developer_prefix
def src_config_url, src_config_ref, s3_bucket, fcos_config_commit
node {
    checkout scm
    pipeutils = load("utils.groovy")
    streams = load("streams.groovy")
    pod = readFile(file: "manifests/pod.yaml")

    // just autodetect if we're in the official prod Jenkins or not
    official_jenkins = (env.JENKINS_URL in ['https://jenkins-fedora-coreos.apps.ocp.ci.centos.org/',
                                            'https://jenkins-fedora-coreos.apps.ocp.stg.fedoraproject.org/'])
    def official_job = (env.JOB_NAME == 'fedora-coreos/fedora-coreos-fedora-coreos-pipeline')
    official = (official_jenkins && official_job)

    if (official) {
        echo "Running in official (prod) mode."
    } else {
        echo "Running in developer mode on ${env.JENKINS_URL}."
    }

    developer_prefix = pipeutils.get_pipeline_annotation('developer-prefix')
    src_config_url = pipeutils.get_pipeline_annotation('source-config-url')
    src_config_ref = pipeutils.get_pipeline_annotation('source-config-ref')
    s3_bucket = pipeutils.get_pipeline_annotation('s3-bucket')
    gcp_gs_bucket = pipeutils.get_pipeline_annotation('gcp-gs-bucket')

    // sanity check that a valid prefix is provided if in devel mode and drop
    // the trailing '-' in the devel prefix
    if (!official) {
      assert developer_prefix.length() > 0 : "Missing developer prefix"
      assert developer_prefix.endsWith("-") : "Missing trailing dash in developer prefix"
      developer_prefix = developer_prefix[0..-2]
    }
}

// Share with the Fedora testing account so we can test it afterwards
FEDORA_AWS_TESTING_USER_ID = "013116697141"

// Base URL through which to download artifacts
BUILDS_BASE_HTTP_URL = "https://builds.coreos.fedoraproject.org/prod/streams"

def coreos_assembler_image
if (official) {
    coreos_assembler_image = "coreos-assembler:main"
} else {
    coreos_assembler_image = "${developer_prefix}-coreos-assembler:main"
}

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
      booleanParam(name: 'FORCE',
                   defaultValue: false,
                   description: 'Whether to force a rebuild'),
      booleanParam(name: 'MINIMAL',
                   defaultValue: (official ? false : true),
                   description: 'Whether to only build the OSTree and qemu images'),
      booleanParam(name: 'ALLOW_KOLA_UPGRADE_FAILURE',
                   defaultValue: false,
                   description: "Don't error out if upgrade tests fail (temporary)"),
      // use a string here because passing booleans via `oc start-build -e`
      // is non-trivial
      choice(name: 'AWS_REPLICATION',
             choices: (['false', 'true']),
             description: 'Force AWS AMI replication for non-production'),
      string(name: 'COREOS_ASSEMBLER_IMAGE',
             description: 'Override coreos-assembler image to use',
             defaultValue: "${coreos_assembler_image}",
             trim: true),
    ]),
    buildDiscarder(logRotator(
        numToKeepStr: '60',
        artifactNumToKeepStr: '20'
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
def cosa_memory_request_mb
if (official) {
    cosa_memory_request_mb = 6.5 * 1024
} else {
    cosa_memory_request_mb = 2.5 * 1024
}
cosa_memory_request_mb = cosa_memory_request_mb as Integer

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
          if (official) {
            // see bucket layout in https://github.com/coreos/fedora-coreos-tracker/issues/189
            s3_stream_dir = "${s3_bucket}/prod/streams/${params.STREAM}"
          } else {
            // One prefix = one pipeline = one stream; the deploy script is geared
            // towards testing a specific combination of (cosa, pipeline, fcos config),
            // not a full duplication of all the prod streams. One can always instantiate
            // a second prefix to test a separate combination if more than 1 concurrent
            // devel pipeline is needed.
            s3_stream_dir = "${s3_bucket}/devel/streams/${developer_prefix}"
          }
        }

        def developer_builddir = "/srv/devel/${developer_prefix}/build"

        stage('Init') {

            def ref = params.STREAM
            if (src_config_ref != "") {
                assert !official : "Asked to override ref in official mode"
                ref = src_config_ref
            }

            // for now, just use the PVC to keep cache.qcow2 in a stream-specific dir
            def cache_img
            if (official) {
                cache_img = "/srv/prod/${params.STREAM}/cache.qcow2"
            } else {
                cache_img = "/srv/devel/${developer_prefix}/cache.qcow2"
            }

            shwrap("""
            cosa init --force --branch ${ref} ${src_config_url}
            mkdir -p \$(dirname ${cache_img})
            ln -s ${cache_img} cache/cache.qcow2
            """)

            // Capture the exact git commit used. Will pass to multi-arch pipeline runs.
            fcos_config_commit=shwrapCapture("git -C src/config rev-parse HEAD")

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
            } else if (!official && utils.pathExists(developer_builddir)) {
                shwrap("""
                cosa buildfetch --url ${developer_builddir}
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

        if (official && s3_stream_dir && utils.pathExists("/etc/fedora-messaging-cfg/fedmsg.toml")) {
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

        stage('Build QEMU') {
            shwrap("""
            cosa buildextend-qemu
            """)
        }

        stage('Kola:QEMU basic') {
            shwrap("""
            cosa kola run --rerun --basic-qemu-scenarios --no-test-exit-error
            tar -cf - tmp/kola/ | xz -c9 > kola-run-basic.tar.xz
            """)
            archiveArtifacts "kola-run-basic.tar.xz"
        }
        if (!pipeutils.checkKolaSuccess("tmp/kola", currentBuild)) {
            return
        }

        stage('Kola:QEMU') {
            // leave 512M for overhead; VMs are 1G each
            def parallel = ((cosa_memory_request_mb - 512) / 1024) as Integer
            shwrap("""
            cosa kola run --rerun --parallel ${parallel} --no-test-exit-error
            tar -cf - tmp/kola/ | xz -c9 > kola-run.tar.xz
            """)
            archiveArtifacts "kola-run.tar.xz"
        }
        if (!pipeutils.checkKolaSuccess("tmp/kola", currentBuild)) {
            return
        }

        stage('Kola:QEMU upgrade') {
            shwrap("""
            cosa kola --rerun --upgrades --no-test-exit-error
            tar -cf - tmp/kola-upgrade | xz -c9 > kola-run-upgrade.tar.xz
            """)
            archiveArtifacts "kola-run-upgrade.tar.xz"
        }
        if (!params.ALLOW_KOLA_UPGRADE_FAILURE && !pipeutils.checkKolaSuccess("tmp/kola-upgrade", currentBuild)) {
            return
        }

        // Do an Early Archive of just the OSTree. This has the
        // desired side effect of reserving our build ID before
        // we fork off multi-arch builds.
        stage('Archive OSTree') {
            if (s3_stream_dir) {
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

        stage('Fork AARCH64 Pipeline') {
            build job: 'multi-arch-pipeline', wait: false, parameters: [
                booleanParam(name: 'FORCE', value: params.FORCE),
                booleanParam(name: 'MINIMAL', value: params.MINIMAL),
                string(name: 'FCOS_CONFIG_COMMIT', value: fcos_config_commit),
                string(name: 'COREOS_ASSEMBLER_IMAGE', value: params.COREOS_ASSEMBLER_IMAGE),
                string(name: 'STREAM', value: params.STREAM),
                string(name: 'VERSION', value: newBuildID),
                string(name: 'ARCH', value: 'aarch64')
            ]
        }

        if (!params.MINIMAL) {

            stage("Metal") {
                parallel metal: {
                    shwrap("""
                    cosa buildextend-metal
                    """)
                }, metal4k: {
                    shwrap("""
                    cosa buildextend-metal4k
                    """)
                }
            }

            stage('Build Live') {
                shwrap("""
                cosa buildextend-live
                """)
            }

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

            // parallel build these artifacts
            def pbuilds = [:]
            ["Aliyun", "AWS", "Azure", "AzureStack", "DigitalOcean", "Exoscale", "GCP", "IBMCloud", "OpenStack", "VMware", "Vultr"].each {
                pbuilds[it] = {
                    def cmd = it.toLowerCase()
                    shwrap("""
                    cosa buildextend-${cmd}
                    """)
                }
            }
            parallel pbuilds

            // Key off of s3_stream_dir: i.e. if we're configured to upload artifacts
            // to S3, we also take that to mean we should upload an AMI. We could
            // split this into two separate developer knobs in the future.
            if (s3_stream_dir && !is_mechanical) {
                stage('Upload AWS') {
                    def suffix = official ? "" : "--name-suffix ${developer_prefix}"
                    // XXX: hardcode us-east-1 for now
                    // XXX: use the temporary 'ami-import' subpath for now; once we
                    // also publish vmdks, we could make this more efficient by
                    // uploading first, and then pointing ore at our uploaded vmdk
                    shwrap("""
                    export AWS_CONFIG_FILE=\${AWS_FCOS_BUILDS_BOT_CONFIG}
                    cosa buildextend-aws ${suffix} \
                        --upload \
                        --build=${newBuildID} \
                        --region=us-east-1 \
                        --bucket s3://${s3_bucket}/ami-import \
                        --grant-user ${FEDORA_AWS_TESTING_USER_ID}
                    """)
                }
            }

            // If there is a config for GCP then we'll upload our image to GCP
            if (utils.pathExists("\${GCP_IMAGE_UPLOAD_CONFIG}") && !is_mechanical) {
                stage('Upload GCP') {
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
        }

        // Generate KeyLime hashes for attestation on official builds
        // This is a POC setup and will be modified over time
        // See: https://github.com/keylime/enhancements/blob/master/16_remote_allowlist_retrieval.md
        stage('KeyLime Hash Generation') {
            shwrap("""
            cosa generate-hashlist --arch=${basearch} --release=${newBuildID} \
                --output=builds/${newBuildID}/${basearch}/exp-hash.json
            sha256sum builds/${newBuildID}/${basearch}/exp-hash.json \
                > builds/${newBuildID}/${basearch}/exp-hash.json-CHECKSUM
            """)
        }

        stage('Archive') {
            // lower to make sure we don't go over and account for overhead
            def xz_memlimit = cosa_memory_request_mb - 512
            shwrap("""
            export XZ_DEFAULTS=--memlimit=${xz_memlimit}Mi
            cosa compress --compressor xz
            """)

            if (s3_stream_dir) {
              // just upload as public-read for now, but see discussions in
              // https://github.com/coreos/fedora-coreos-tracker/issues/189
              shwrap("""
              export AWS_CONFIG_FILE=\${AWS_FCOS_BUILDS_BOT_CONFIG}
              cosa buildupload --skip-builds-json \
                  s3 --acl=public-read ${s3_stream_dir}/builds
              """)
            } else if (!official) {
              // In devel mode without an S3 server, just archive into the PVC
              // itself. Otherwise there'd be no other way to retrieve the
              // artifacts. But note we only keep one build at a time.
              shwrap("""
              rm -rf ${developer_builddir}
              mkdir -p ${developer_builddir}
              cp -aT builds ${developer_builddir}
              """)
            }
        }

        // These steps interact with Fedora Infrastructure/Releng for
        // signing of artifacts and importing of OSTree commits. They
        // must be run after the archive stage because the artifacts
        // are pulled from their S3 locations.
        if (official && s3_stream_dir && utils.pathExists("/etc/fedora-messaging-cfg/fedmsg.toml")) {
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
        if (!params.MINIMAL && s3_stream_dir &&
                utils.pathExists("\${AWS_FCOS_KOLA_BOT_CONFIG}") && !is_mechanical) {
            stage('Kola:AWS') {
                // We consider the AWS kola tests to be a followup job, so we use `wait: false` here.
                build job: 'kola-aws', wait: false, parameters: [
                    string(name: 'STREAM', value: params.STREAM),
                    string(name: 'VERSION', value: newBuildID),
                    string(name: 'S3_STREAM_DIR', value: s3_stream_dir),
                    string(name: 'FCOS_CONFIG_COMMIT', value: fcos_config_commit)
                ]
            }
        }
        if (!params.MINIMAL && s3_stream_dir &&
                utils.pathExists("\${GCP_KOLA_TESTS_CONFIG}") && !is_mechanical) {
            stage('Kola:GCP') {
                // We consider the GCP kola tests to be a followup job, so we use `wait: false` here.
                build job: 'kola-gcp', wait: false, parameters: [
                    string(name: 'STREAM', value: params.STREAM),
                    string(name: 'VERSION', value: newBuildID),
                    string(name: 'S3_STREAM_DIR', value: s3_stream_dir),
                    string(name: 'FCOS_CONFIG_COMMIT', value: fcos_config_commit)
                ]
            }
        }
        if (!params.MINIMAL && s3_stream_dir &&
                utils.pathExists("\${OPENSTACK_KOLA_TESTS_CONFIG}") && !is_mechanical) {
            stage('Kola:OpenStack') {
                // We consider the OpenStack kola tests to be a followup job, so we use `wait: false` here.
                build job: 'kola-openstack', wait: false, parameters: [
                    string(name: 'STREAM', value: params.STREAM),
                    string(name: 'VERSION', value: newBuildID),
                    string(name: 'S3_STREAM_DIR', value: s3_stream_dir),
                    string(name: 'FCOS_CONFIG_COMMIT', value: fcos_config_commit)
                ]
            }
        }

        // For now, we auto-release all non-production streams builds. That
        // way, we can e.g. test testing-devel AMIs easily.
        //
        // Since we are only running this stage for non-production (i.e. mechanical
        // and development) builds we'll default to not doing AWS AMI replication.
        // That can be overridden by the user setting the AWS_REPLICATION parameter
        // to true, overriding the default (false).
        if (official && !(params.STREAM in streams.production)) {
            stage('Publish') {
                // use jnlp container in our pod, which has `oc` in it already
                container('jnlp') {
                    shwrap("""
                    oc start-build --wait fedora-coreos-pipeline-release \
                        -e STREAM=${params.STREAM} \
                        -e ARCHES=${basearch} \
                        -e VERSION=${newBuildID} \
                        -e AWS_REPLICATION=${params.AWS_REPLICATION}
                    """)
                }
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

            try {
                if (official) {
                    slackSend(color: color, message: message)
                    shwrap("""
                    /var/tmp/fcos-releng/scripts/broadcast-fedmsg.py --fedmsg-conf=/etc/fedora-messaging-cfg/fedmsg.toml \
                        build.state.change --build ${newBuildID} --basearch ${basearch} --stream ${params.STREAM} \
                        --build-dir ${BUILDS_BASE_HTTP_URL}/${params.STREAM}/builds/${newBuildID}/${basearch} \
                        --state FINISHED --result ${currentBuild.result}
                    """)
                }
            } finally {
                echo message
            }
        }
    }}
}}
