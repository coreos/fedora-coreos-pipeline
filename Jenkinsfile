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

properties([
    pipelineTriggers([]),
    parameters([
      choice(name: 'STREAM',
             // list devel first so that it's the default choice
             choices: (streams.development + streams.production + streams.mechanical),
             description: 'Fedora CoreOS stream to build',
             required: true),
      // XXX: Temporary parameter for first few FCOS preview releases. We
      // eventually want some way to drive this automatically as per the
      // versioning scheme.
      // https://github.com/coreos/fedora-coreos-tracker/issues/212
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
      // use a string here because passing booleans via `oc start-build -e`
      // is non-trivial
      choice(name: 'AWS_REPLICATION',
             choices: (['false', 'true']),
             defaultValue: 'false',
             description: 'Force AWS AMI replication for non-production')
    ]),
    buildDiscarder(logRotator(
        numToKeepStr: '60',
        artifactNumToKeepStr: '3'
    ))
])

currentBuild.description = "[${params.STREAM}] Running"

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
if (official) {
    pod = pod.replace("COREOS_ASSEMBLER_IMAGE", "coreos-assembler:master")
} else {
    pod = pod.replace("COREOS_ASSEMBLER_IMAGE", "${developer_prefix}-coreos-assembler:master")
}

podTemplate(cloud: 'openshift', label: 'coreos-assembler', yaml: pod, defaultContainer: 'jnlp') {
    node('coreos-assembler') { container('coreos-assembler') {

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
        def basearch = utils.shwrap_capture("coreos-assembler basearch")

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
            coreos-assembler init --force --branch ${ref} ${src_config_url}
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

        stage('Fetch') {
            if (s3_stream_dir) {
                utils.shwrap("""
                export AWS_CONFIG_FILE=\${AWS_FCOS_BUILDS_BOT_CONFIG}
                coreos-assembler buildprep s3://${s3_stream_dir}/builds
                """)
                // also fetch releases.json to get the latest build, but don't error if it doesn't exist
                utils.aws_s3_cp_allow_noent("s3://${s3_stream_dir}/releases.json", "tmp/releases.json")
            } else if (!official && utils.path_exists(developer_builddir)) {
                utils.shwrap("""
                coreos-assembler buildprep ${developer_builddir}
                """)
            }

            utils.shwrap("""
            coreos-assembler fetch
            """)
        }

        def prevBuildID = null
        if (utils.path_exists("builds/latest")) {
            prevBuildID = utils.shwrap_capture("readlink builds/latest")
        }

        def parent_version = ""
        def parent_commit = ""
        stage('Build') {
            def parent_arg = ""
            if (utils.path_exists("tmp/releases.json")) {
                def releases = readJSON file: "tmp/releases.json"
                def commit_obj = releases["releases"][-1]["commits"].find{ commit -> commit["architecture"] == basearch }
                parent_commit = commit_obj["checksum"]
                parent_arg = "--parent ${parent_commit}"
                parent_version = releases["releases"][-1]["version"]
            }

            def force = params.FORCE ? "--force" : ""
            def version = params.VERSION ? "--version ${params.VERSION}" : ""
            utils.shwrap("""
            coreos-assembler build ostree --skip-prune ${force} ${version} ${parent_arg}
            """)
        }

        def newBuildID = utils.shwrap_capture("readlink builds/latest")
        if (prevBuildID == newBuildID) {
            currentBuild.result = 'SUCCESS'
            currentBuild.description = "[${params.STREAM}] 💤 (no new build)"
            return
        } else {
            currentBuild.description = "[${params.STREAM}] ⚡ ${newBuildID}"

            // and insert the parent info into meta.json so we can display it in
            // the release browser and for sanity checking
            if (parent_commit && parent_version) {
                def meta_json = "builds/${newBuildID}/${basearch}/meta.json"
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
            coreos-assembler buildextend-qemu
            """)
        }

        stage('Kola:QEMU') {
            utils.shwrap("""
            coreos-assembler kola run || :
            tar -cf - tmp/kola/ | xz -c9 > _kola_temp.tar.xz
            """)
            archiveArtifacts "_kola_temp.tar.xz"
        }

        // archive the image if the tests failed
        def report = readJSON file: "tmp/kola/reports/report.json"
        if (report["result"] != "PASS") {
            utils.shwrap("coreos-assembler compress --compressor xz")
            archiveArtifacts "builds/latest/**/*.qcow2.xz"
            currentBuild.result = 'FAILURE'
            return
        }

        if (!params.MINIMAL) {
            stage('Build Metal') {
                utils.shwrap("""
                coreos-assembler buildextend-metal
                """)
            }

            stage('Build Installer') {
                utils.shwrap("""
                coreos-assembler buildextend-installer
                """)
            }

            stage('Build Live') {
                utils.shwrap("""
                coreos-assembler buildextend-live
                """)
            }

            stage('Build Openstack') {
                utils.shwrap("""
                coreos-assembler buildextend-openstack
                """)
            }

            stage('Build Aliyun') {
                utils.shwrap("""
                coreos-assembler buildextend-aliyun
                """)
            }

            stage('Build VMware') {
                utils.shwrap("""
                coreos-assembler buildextend-vmware
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
                    coreos-assembler buildextend-aws ${suffix} \
                        --upload \
                        --build=${newBuildID} \
                        --region=us-east-1 \
                        --bucket s3://${s3_bucket}/ami-import \
                        --grant-user ${FEDORA_AWS_TESTING_USER_ID}
                    """)
                }
            }
        }

        stage('Archive') {
            // lower to make sure we don't go over and account for overhead
            def xz_memlimit = cosa_memory_request_mb - 512
            utils.shwrap("""
            export XZ_DEFAULTS=--memlimit=${xz_memlimit}Mi
            coreos-assembler compress --compressor xz
            """)

            // Run the coreos-meta-translator against the most recent build,
            // which will generate a release.json from the meta.json files
            utils.shwrap("""
            git clone https://github.com/coreos/fedora-coreos-releng-automation /var/tmp/fcos-releng
            /var/tmp/fcos-releng/coreos-meta-translator/trans.py --workdir .
            """)

            if (s3_stream_dir) {
              // just upload as public-read for now, but see discussions in
              // https://github.com/coreos/fedora-coreos-tracker/issues/189
              utils.shwrap("""
              export AWS_CONFIG_FILE=\${AWS_FCOS_BUILDS_BOT_CONFIG}
              coreos-assembler buildupload s3 --acl=public-read ${s3_stream_dir}/builds
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
    }}
}
