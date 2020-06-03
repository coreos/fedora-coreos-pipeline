import org.yaml.snakeyaml.Yaml;

def utils, streams, official, official_jenkins, developer_prefix, src_config_url, src_config_ref, s3_bucket
node {
    checkout scm
    utils = load("utils.groovy")
    streams = load("streams.groovy")
    pod = readFile(file: "manifests/pod.yaml")

    // just autodetect if we're in the official prod Jenkins or not
    official_jenkins = (env.JENKINS_URL == 'https://jenkins-fedora-coreos.apps.ci.centos.org/')
    def official_job = (env.JOB_NAME == 'fedora-coreos/fedora-coreos-fedora-coreos-pipeline')
    official = (official_jenkins && official_job)

    if (official) {
        echo "Running in official (prod) mode."
    } else {
        echo "Running in developer mode on ${env.JENKINS_URL}."
    }

    developer_prefix = utils.get_pipeline_annotation('developer-prefix')
    src_config_url = utils.get_pipeline_annotation('source-config-url')
    src_config_ref = utils.get_pipeline_annotation('source-config-ref')
    s3_bucket = utils.get_pipeline_annotation('s3-bucket')
    kvm_selector = utils.get_pipeline_annotation('kvm-selector')
    gcp_gs_bucket = utils.get_pipeline_annotation('gcp-gs-bucket')

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

def coreos_assembler_image
if (official) {
    coreos_assembler_image = "coreos-assembler:master"
} else {
    coreos_assembler_image = "${developer_prefix}-coreos-assembler:master"
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
        artifactNumToKeepStr: '3'
    )),
    durabilityHint('PERFORMANCE_OPTIMIZED')
])

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
// And the KVM selector
def cosaContainer = podYaml['spec']['containers'][1];
switch (kvm_selector) {
    case 'kvm-device-plugin':
        def resources = cosaContainer['resources'];
        def kvmres = 'devices.kubevirt.io/kvm';
        resources['requests'][kvmres] = '1';
        resources['limits'][kvmres] = '1';
        break;
    case 'legacy-oci-kvm-hook':
        cosaContainer['nodeSelector'] = ['oci_kvm_hook': 'allowed'];
        break;
    default:
        throw new Exception("Unknown KVM selector: ${kvm_selector}")
}

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

        // first, print out the list of pods for debugging purposes
        container('jnlp') {
            utils.shwrap("oc get pods -o wide")
        }

        // declare this early so we can use it in Slack
        def newBuildID

        try { timeout(time: 240, unit: 'MINUTES') {

        // Clone the automation repo, which contains helper scripts. In the
        // future, we'll probably want this either part of the cosa image, or
        // in a derivative of cosa for pipeline needs.
        utils.shwrap("""
        git clone --depth=1 https://github.com/coreos/fedora-coreos-releng-automation /var/tmp/fcos-releng
        """)

        // this is defined IFF we *should* and we *can* upload to S3
        def s3_stream_dir

        if (s3_bucket && utils.path_exists("\${AWS_FCOS_BUILDS_BOT_CONFIG}")) {
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
        def basearch = utils.shwrap_capture("cosa basearch")

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

            utils.shwrap("""
            cosa init --force --branch ${ref} ${src_config_url}
            mkdir -p \$(dirname ${cache_img})
            ln -s ${cache_img} cache/cache.qcow2
            """)

            // If the cache img is larger than 7G, then nuke it. Otherwise
            // it'll just keep growing and we'll hit ENOSPC. It'll get rebuilt.
            utils.shwrap("""
            if [ -f ${cache_img} ] && [ \$(du ${cache_img} | cut -f1) -gt \$((1024*1024*7)) ]; then
                rm -vf ${cache_img}
            fi
            """)
        }

        def parent_version = ""
        def parent_commit = ""
        stage('Fetch') {
            if (s3_stream_dir) {
                utils.aws_s3_cp_allow_noent("s3://${s3_stream_dir}/releases.json", "tmp/releases.json")
                if (utils.path_exists("tmp/releases.json")) {
                    def releases = readJSON file: "tmp/releases.json"
                    // check if there's a previous release we should use as parent
                    if (releases["releases"].size() > 0) {
                        def commit_obj = releases["releases"][-1]["commits"].find{ commit -> commit["architecture"] == basearch }
                        parent_commit = commit_obj["checksum"]
                        parent_version = releases["releases"][-1]["version"]
                    }
                }

                utils.shwrap("""
                export AWS_CONFIG_FILE=\${AWS_FCOS_BUILDS_BOT_CONFIG}
                cosa buildprep s3://${s3_stream_dir}/builds
                """)
                if (parent_version != "") {
                    // also fetch the parent version; this is used by cosa to do the diff
                    utils.shwrap("""
                    cosa buildprep s3://${s3_stream_dir}/builds --build ${parent_version}
                    """)
                }
            } else if (!official && utils.path_exists(developer_builddir)) {
                utils.shwrap("""
                cosa buildprep ${developer_builddir}
                """)
            }

            utils.shwrap("""
            cosa fetch --strict
            """)
        }

        def prevBuildID = null
        if (utils.path_exists("builds/latest")) {
            prevBuildID = utils.shwrap_capture("readlink builds/latest")
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
                def new_version = utils.shwrap_capture("/var/tmp/fcos-releng/scripts/versionary.py")
                version = "--version ${new_version}"
            }
            utils.shwrap("""
            cosa build ostree --strict --skip-prune ${force} ${version} ${parent_arg}
            """)
        }

        def meta_json
        def buildID = utils.shwrap_capture("readlink builds/latest")
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
        }

        if (official && s3_stream_dir && utils.path_exists("/etc/fedora-messaging-cfg/fedmsg.toml")) {
            stage('Sign OSTree') {
                utils.shwrap("""
                export AWS_CONFIG_FILE=\${AWS_FCOS_BUILDS_BOT_CONFIG}
                cosa sign robosignatory --s3 ${s3_stream_dir}/builds \
                    --extra-fedmsg-keys stream=${params.STREAM} \
                    --ostree --gpgkeypath /etc/pki/rpm-gpg \
                    --fedmsg-conf /etc/fedora-messaging-cfg/fedmsg.toml
                """)
            }
        }

        stage('Build QEMU') {
            utils.shwrap("""
            cosa buildextend-qemu
            """)
        }

        stage('Kola:QEMU basic') {
            utils.shwrap("""
            cosa kola run --basic-qemu-scenarios --no-test-exit-error
            tar -cf - tmp/kola/ | xz -c9 > kola-run-basic.tar.xz
            """)
            archiveArtifacts "kola-run-basic.tar.xz"
        }
        if (!utils.checkKolaSuccess("tmp/kola", currentBuild)) {
            return
        }

        stage('Kola:QEMU') {
            // leave 512M for overhead; VMs are 1G each
            def parallel = ((cosa_memory_request_mb - 512) / 1024) as Integer
            utils.shwrap("""
            cosa kola run --parallel ${parallel} --no-test-exit-error
            tar -cf - tmp/kola/ | xz -c9 > kola-run.tar.xz
            """)
            archiveArtifacts "kola-run.tar.xz"
        }
        if (!utils.checkKolaSuccess("tmp/kola", currentBuild)) {
            return
        }

        stage('Kola:QEMU upgrade') {
            utils.shwrap("""
            cosa kola --upgrades --no-test-exit-error
            tar -cf - tmp/kola-upgrade | xz -c9 > kola-run-upgrade.tar.xz
            """)
            archiveArtifacts "kola-run-upgrade.tar.xz"
        }
        if (!params.ALLOW_KOLA_UPGRADE_FAILURE && !utils.checkKolaSuccess("tmp/kola-upgrade", currentBuild)) {
            return
        }

        if (!params.MINIMAL) {
            stage('Build Metal') {
                utils.shwrap("""
                cosa buildextend-metal
                """)
            }

            stage('Build Metal (4K Native)') {
                utils.shwrap("""
                cosa buildextend-metal4k
                """)
            }

            stage('Build Live') {
                utils.shwrap("""
                cosa buildextend-live
                """)
            }

            stage('Test Live ISO') {
                // compress the metal and metal4k images now so that each test
                // doesn't have to compress them
                // lower to make sure we don't go over and account for overhead
                def xz_memlimit = cosa_memory_request_mb - 512
                utils.shwrap("""
                export XZ_DEFAULTS=--memlimit=${xz_memlimit}Mi
                cosa compress --compressor xz --artifact metal --artifact metal4k
                """)
                try {
                    parallel metal: {
                        utils.shwrap("kola testiso -S --output-dir tmp/kola-metal")
                    }, metal4k: {
                        utils.shwrap("kola testiso -SP --qemu-native-4k --output-dir tmp/kola-metal4k")
                    }
                } catch (Throwable e) {
                    archiveArtifacts "builds/latest/**/*.iso"
                    throw e
                } finally {
                    utils.shwrap("tar -cf - tmp/kola-metal/ | xz -c9 > ${env.WORKSPACE}/kola-testiso-metal.tar.xz")
                    utils.shwrap("tar -cf - tmp/kola-metal4k/ | xz -c9 > ${env.WORKSPACE}/kola-testiso-metal4k.tar.xz")
                    archiveArtifacts allowEmptyArchive: true, artifacts: 'kola-testiso*.tar.xz'
                }
            }

            stage('Build Azure') {
                utils.shwrap("""
                cosa buildextend-azure
                """)
            }

            stage('Build Exoscale') {
                utils.shwrap("""
                cosa buildextend-exoscale
                """)
            }

            stage('Build Openstack') {
                utils.shwrap("""
                cosa buildextend-openstack
                """)
            }

            stage('Build Aliyun') {
                utils.shwrap("""
                cosa buildextend-aliyun
                """)
            }

            stage('Build Vultr') {
                utils.shwrap("""
                cosa buildextend-vultr
                """)
            }

            stage('Build VMware') {
                utils.shwrap("""
                cosa buildextend-vmware
                """)
            }

            stage('Build GCP') {
                utils.shwrap("""
                cosa buildextend-gcp
                """)
            }

            stage('Build DigitalOcean') {
                utils.shwrap("""
                cosa buildextend-digitalocean
                """)
            }

            // Key off of s3_stream_dir: i.e. if we're configured to upload artifacts
            // to S3, we also take that to mean we should upload an AMI. We could
            // split this into two separate developer knobs in the future.
            if (s3_stream_dir) {
                stage('Upload AWS') {
                    def suffix = official ? "" : "--name-suffix ${developer_prefix}"
                    // XXX: hardcode us-east-1 for now
                    // XXX: use the temporary 'ami-import' subpath for now; once we
                    // also publish vmdks, we could make this more efficient by
                    // uploading first, and then pointing ore at our uploaded vmdk
                    utils.shwrap("""
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
            if (utils.path_exists("\${GCP_IMAGE_UPLOAD_CONFIG}")) {
                stage('Upload GCP') {
                    utils.shwrap("""
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
                        --description=\"Fedora CoreOS, Fedora CoreOS ${params.STREAM}, ${newBuildID}, ${basearch} published on \$today\"
                    """)
                }
            }
        }

        stage('Archive') {
            // lower to make sure we don't go over and account for overhead
            def xz_memlimit = cosa_memory_request_mb - 512
            utils.shwrap("""
            export XZ_DEFAULTS=--memlimit=${xz_memlimit}Mi
            cosa compress --compressor xz
            """)

            // Run the coreos-meta-translator against the most recent build,
            // which will generate a release.json from the meta.json files
            utils.shwrap("""
            /var/tmp/fcos-releng/coreos-meta-translator/trans.py --workdir .
            """)

            if (s3_stream_dir) {
              // just upload as public-read for now, but see discussions in
              // https://github.com/coreos/fedora-coreos-tracker/issues/189
              utils.shwrap("""
              export AWS_CONFIG_FILE=\${AWS_FCOS_BUILDS_BOT_CONFIG}
              cosa buildupload s3 --acl=public-read ${s3_stream_dir}/builds
              """)
            } else if (!official) {
              // In devel mode without an S3 server, just archive into the PVC
              // itself. Otherwise there'd be no other way to retrieve the
              // artifacts. But note we only keep one build at a time.
              utils.shwrap("""
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
        if (official && s3_stream_dir && utils.path_exists("/etc/fedora-messaging-cfg/fedmsg.toml")) {
            stage('Sign Images') {
                utils.shwrap("""
                export AWS_CONFIG_FILE=\${AWS_FCOS_BUILDS_BOT_CONFIG}
                cosa sign robosignatory --s3 ${s3_stream_dir}/builds \
                    --extra-fedmsg-keys stream=${params.STREAM} \
                    --images --gpgkeypath /etc/pki/rpm-gpg \
                    --fedmsg-conf /etc/fedora-messaging-cfg/fedmsg.toml
                """)
            }
            stage("OSTree Import: Compose Repo") {
                utils.shwrap("""
                /var/tmp/fcos-releng/coreos-ostree-importer/send-ostree-import-request.py \
                    --build=${newBuildID} --s3=${s3_stream_dir} --repo=compose \
                    --fedmsg-conf=/etc/fedora-messaging-cfg/fedmsg.toml
                """)
            }
        }

        // Now that the metadata is uploaded go ahead and kick off some tests
        if (!params.MINIMAL && s3_stream_dir &&
                utils.path_exists("\${AWS_FCOS_KOLA_BOT_CONFIG}")) {
            stage('Kola:AWS') {
                // use jnlp container in our pod, which has `oc` in it already
                container('jnlp') {
                    utils.shwrap("""
                        # We consider the AWS kola tests to be a followup job
                        # so we aren't adding a `--wait` here.
                        oc start-build fedora-coreos-pipeline-kola-aws \
                            -e STREAM=${params.STREAM} \
                            -e VERSION=${newBuildID} \
                            -e S3_STREAM_DIR=${s3_stream_dir}
                    """)
                }
            }
        }

        // Now that the metadata is uploaded go ahead and kick off some tests
        if (!params.MINIMAL && s3_stream_dir &&
                utils.path_exists("\${GCP_IMAGE_UPLOAD_CONFIG}")) {
            stage('Kola:GCP') {
                // use jnlp container in our pod, which has `oc` in it already
                container('jnlp') {
                    utils.shwrap("""
                        # We consider the GCP kola tests to be a followup job
                        # so we aren't adding a `--wait` here.
                        oc start-build fedora-coreos-pipeline-kola-gcp \
                            -e STREAM=${params.STREAM} \
                            -e VERSION=${newBuildID} \
                            -e S3_STREAM_DIR=${s3_stream_dir}
                    """)
                }
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
                    utils.shwrap("""
                    oc start-build --wait fedora-coreos-pipeline-release \
                        -e STREAM=${params.STREAM} \
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
                }
            } finally {
                echo message
            }
        }
    }}
}}
