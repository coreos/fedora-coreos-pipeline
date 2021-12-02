import org.yaml.snakeyaml.Yaml;

def pipeutils, streams, official, developer_prefix, repo, src_config_url, src_config_ref, s3_bucket, gp
node {
    checkout scm
    pipeutils = load("utils.groovy")
    streams = load("streams.groovy")
    pod = readFile(file: "manifests/pod.yaml")
    gp = load("gp.groovy")
    repo = "coreos/fedora-coreos-config"


    // just autodetect if we're in the official prod Jenkins or not
    official_jenkins = (env.JENKINS_URL in ['https://jenkins-fedora-coreos.apps.ocp.ci.centos.org/',
                                            'https://jenkins-fedora-coreos.apps.ocp.stg.fedoraproject.org/'])

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
      string(name: 'ARCH',
             description: 'The target architecture',
             defaultValue: 'aarch64',
             trim: true),
      string(name: 'FCOS_CONFIG_COMMIT',
             description: 'The exact config repo git commit to build against',
             defaultValue: '',
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

// Note that the heavy lifting is done on a remote node via gangplank
// so we shouldn't need much memory.
def cosa_memory_request_mb = 2.5 * 1024
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


echo "Waiting for build-${params.STREAM}-${params.ARCH} lock"
currentBuild.description = "[${params.STREAM}][${params.ARCH}] Waiting"

lock(resource: "build-${params.STREAM}-${params.ARCH}") {
    currentBuild.description = "[${params.STREAM}][${params.ARCH}] Running"

    podTemplate(cloud: 'openshift', label: pod_label, yaml: pod) {
    node(pod_label) { container('coreos-assembler') {

        // print out details of the cosa image to help debugging
        shwrap("""
        cat /cosa/coreos-assembler-git.json
        """)

        // declare these early so we can use them in `finally` block
        def newBuildID = params.VERSION
        def basearch = params.ARCH

        // We currently have a limitation where we aren't building and
        // pushing multi-arch COSA containers to quay. For multi-arch
        // we're currently building images once a day on the local
        // multi-arch builders. See https://github.com/coreos/coreos-assembler/issues/2470
        //
        // Until #2470 is resolved let's do the best thing we can do
        // which is derive the multi-arch container name from the
        // given x86_64 COSA container. We'll translate
        // quay.io/coreos-assembler/coreos-assembler:$tag -> localhost/coreos-assembler:$tag
        // This assumes that the desired tagged image has been built
        // on the multi-arch builder already, which most likely means
        // someone did it manually.
        def image = "localhost/coreos-assembler:latest"
        if (params.COREOS_ASSEMBLER_IMAGE.startsWith("quay.io/coreos-assembler/coreos-assembler:")) {
            image = params.COREOS_ASSEMBLER_IMAGE.replaceAll(
                "quay.io/coreos-assembler/coreos-assembler:",
                "localhost/coreos-assembler:"
            )
        }

        try { timeout(time: 240, unit: 'MINUTES') {

        // Clone the automation repo, which contains helper scripts. In the
        // future, we'll probably want this either part of the cosa image, or
        // in a derivative of cosa for pipeline needs.
        shwrap("""
        git clone --depth=1 https://github.com/coreos/fedora-coreos-releng-automation /var/tmp/fcos-releng
        """)

        if (official) {
            shwrap("""
            /var/tmp/fcos-releng/scripts/broadcast-fedmsg.py --fedmsg-conf=/etc/fedora-messaging-cfg/fedmsg.toml \
                build.state.change --build ${newBuildID} --basearch ${basearch} --stream ${params.STREAM} \
                --build-dir ${BUILDS_BASE_HTTP_URL}/${params.STREAM}/builds/${newBuildID}/${basearch} \
                --state STARTED
            """)
        }

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

            def commitopt = ''
            if (params.FCOS_CONFIG_COMMIT != '') {
                commitopt = "--commit=${params.FCOS_CONFIG_COMMIT}"
            }
            shwrap("""
            cosa init --force --branch ${ref} ${commitopt} ${src_config_url}
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
                cosa buildfetch --url=s3://${s3_stream_dir}/builds --arch=${basearch}
                """)
                if (parent_version != "") {
                    // also fetch the parent version; this is used by cosa to do the diff
                    shwrap("""
                    export AWS_CONFIG_FILE=\${AWS_FCOS_BUILDS_BOT_CONFIG}
                    cosa buildfetch --url=s3://${s3_stream_dir}/builds --build ${parent_version} --arch=${basearch}
                    """)
                }
            } else if (!official && utils.pathExists(developer_builddir)) {
                shwrap("""
                cosa buildfetch --url=${developer_builddir} --arch=${basearch}
                """)
            }
        }
        def meta_json = "builds/${newBuildID}/${basearch}/meta.json"

        stage('Build OSTree') {
            def parent_arg = ""
            if (parent_version != "") {
                parent_arg = "--parent-build ${parent_version}"
            }
            def version = "--version ${params.VERSION}"
            def force = params.FORCE ? "--force" : ""
            shwrap("""
               cat <<'EOF' > spec.spec
job:
  strict: true
  miniocfgfile: ""
recipe:
  git_ref: ${params.STREAM}
  git_url: https://github.com/${repo}
  git_commit: ${params.FCOS_CONFIG_COMMIT}
copy-build: ${parent_version}
stages:
- id: ExecOrder 1 Stage
  execution_order: 1
  description: Stage 1 execution base
  prep_commands:
    - cat /cosa/coreos-assembler-git.json
  post_commands:
    - cosa fetch ${strict_build_param}
    - cosa build ostree ${strict_build_param} --skip-prune ${force} ${version} ${parent_arg}
delay_meta_merge: false
EOF
                   """)
            gp.gangplankArchWrapper([spec: "spec.spec", arch: basearch, image: image])
            // and insert the parent info into meta.json so we can display it in
            // the release browser and for sanity checking
            if (parent_commit && parent_version) {
                def meta = readJSON file: meta_json
                meta["fedora-coreos.parent-version"] = parent_version
                meta["fedora-coreos.parent-commit"] = parent_commit
                writeJSON file: meta_json, json: meta
            }
        }
        currentBuild.description = "[${params.STREAM}][${params.ARCH}] ⚡ ${newBuildID}"

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

        def kolaSuccess = true
        stage('Build/Test Remaining') {
            try {
                shwrap("""
                   cat <<'EOF' > spec.spec
job:
  strict: true
  miniocfgfile: ""
recipe:
  git_ref: ${params.STREAM}
  git_url: https://github.com/${repo}
  git_commit: ${params.FCOS_CONFIG_COMMIT}
stages:
- id: ExecOrder 1 Stage
  execution_order: 1
  description: Stage 1 execution base
  prep_commands:
    - cat /cosa/coreos-assembler-git.json
  require_artifacts: [ostree]
  commands:
    - cosa buildextend-qemu
    - cosa kola run --rerun --basic-qemu-scenarios --output-dir tmp/kola-basic
    - cosa kola run --rerun --parallel 4 --output-dir tmp/kola
    - cosa buildextend-metal
    - cosa buildextend-metal4k
    - cosa buildextend-live
    - kola testiso -S --output-dir tmp/kola-metal
    - kola testiso -SP --qemu-native-4k --scenarios iso-install --output-dir tmp/kola-metal4k
    - cosa buildextend-openstack
    # Hack for serial console on aarch64 aws images
    # see https://github.com/coreos/fedora-coreos-tracker/issues/920#issuecomment-914334988
    - echo 'ZGlmZiAtLWdpdCBhL3Vzci9saWIvY29yZW9zLWFzc2VtYmxlci9nZi1wbGF0Zm9ybWlkIGIvdXNyL2xpYi9jb3Jlb3MtYXNzZW1ibGVyL2dmLXBsYXRmb3JtaWQKaW5kZXggNDI5Y2ExYmViLi44MTIzNTk0NjkgMTAwNzU1Ci0tLSBhL3Vzci9saWIvY29yZW9zLWFzc2VtYmxlci9nZi1wbGF0Zm9ybWlkCisrKyBiL3Vzci9saWIvY29yZW9zLWFzc2VtYmxlci9nZi1wbGF0Zm9ybWlkCkBAIC00Niw3ICs0NiwxMSBAQCBibHNjZmdfcGF0aD0kKGNvcmVvc19nZiBnbG9iLWV4cGFuZCAvYm9vdC9sb2FkZXIvZW50cmllcy9vc3RyZWUtKi5jb25mKQogY29yZW9zX2dmIGRvd25sb2FkICIke2Jsc2NmZ19wYXRofSIgIiR7dG1wZH0iL2Jscy5jb25mCiAjIFJlbW92ZSBhbnkgcGxhdGZvcm1pZCBjdXJyZW50bHkgdGhlcmUKIHNlZCAtaSAtZSAncywgaWduaXRpb24ucGxhdGZvcm0uaWQ9W2EtekEtWjAtOV0qLCxnJyAiJHt0bXBkfSIvYmxzLmNvbmYKLXNlZCAtaSAtZSAnL15vcHRpb25zIC8gcywkLCBpZ25pdGlvbi5wbGF0Zm9ybS5pZD0nIiR7cGxhdGZvcm1pZH0iJywnICIke3RtcGR9Ii9ibHMuY29uZgoraWYgWyAiJHtwbGF0Zm9ybWlkfSIgPT0gJ2F3cycgXTsgdGhlbgorICAgIHNlZCAtaSAtZSAnc3xeXChvcHRpb25zIC4qXCl8XDEgaWduaXRpb24ucGxhdGZvcm0uaWQ9JyIke3BsYXRmb3JtaWR9IicgY29uc29sZT10dHlTMCwxMTUyMDBuOHwnICIke3RtcGR9Ii9ibHMuY29uZgorZWxzZQorICAgIHNlZCAtaSAtZSAnL15vcHRpb25zIC8gcywkLCBpZ25pdGlvbi5wbGF0Zm9ybS5pZD0nIiR7cGxhdGZvcm1pZH0iJywnICIke3RtcGR9Ii9ibHMuY29uZgorZmkKIGNvcmVvc19nZiB1cGxvYWQgIiR7dG1wZH0iL2Jscy5jb25mICIke2Jsc2NmZ19wYXRofSIKIAogaWYgWyAiJGJhc2VhcmNoIiA9ICJzMzkweCIgXSA7IHRoZW4K' | base64 --decode | sudo patch /usr/lib/coreos-assembler/gf-platformid
    - cosa buildextend-aws
  post_commands:
    - cosa compress --compressor xz
    - tar --xz -cf tmp/kola.tar.xz tmp/kola{-basic,,-metal,-metal4k} || true
  post_always: true
delay_meta_merge: false
EOF
                """)
                gp.gangplankArchWrapper([spec: "spec.spec", arch: basearch, image: image])
            } catch (Throwable e) {
                throw e
            } finally {
                archiveArtifacts allowEmptyArchive: true, artifacts: "builds/${newBuildID}/${basearch}/logs/kola.tar.xz"
                // Now that the logs are archived remove them from the builds dir
                shwrap("rm -rf builds/${newBuildID}/${basearch}/logs")
            }
        }



        // Key off of s3_stream_dir: i.e. if we're configured to upload artifacts
        // to S3, we also take that to mean we should upload an AMI. We could
        // split this into two separate developer knobs in the future.
        if (s3_stream_dir && !is_mechanical) {
            stage('Upload AWS') {
                def suffix = official ? "" : "--name-suffix ${developer_prefix}"
                // pick up the AWS compressed vmdk and uncompress it
                def meta = readJSON file: meta_json
                def aws_image_filenamexz = meta.images.aws.path
                def aws_image_filename = meta.images.aws.path.minus('.xz')
                def aws_image_sha256 = meta.images.aws.sha256
                def aws_image_filepathxz = "builds/${params.VERSION}/${basearch}/${aws_image_filenamexz}"
                def aws_image_filepath = aws_image_filepathxz.minus('.xz')
                meta['images']['aws']['path'] = aws_image_filename
                writeJSON file: meta_json, json: meta
                shwrap("""
                echo "${aws_image_sha256} ${aws_image_filepathxz}" | sha256sum --check
                xzcat ${aws_image_filepathxz} > ${aws_image_filepath}
                """)
                // XXX: hardcode us-east-1 for now
                // XXX: use the temporary 'ami-import' subpath for now; once we
                // also publish vmdks, we could make this more efficient by
                // uploading first, and then pointing ore at our uploaded vmdk
                shwrap("""
                export AWS_CONFIG_FILE=\${AWS_FCOS_BUILDS_BOT_CONFIG}
                cosa buildextend-aws ${suffix} \
                    --upload \
                    --arch=${basearch} \
                    --build=${newBuildID} \
                    --region=us-east-1 \
                    --bucket s3://${s3_bucket}/ami-import \
                    --grant-user ${FEDORA_AWS_TESTING_USER_ID}
                """)
                // re-read json since AMI was added to it
                meta = readJSON file: meta_json
                meta['images']['aws']['path'] = aws_image_filenamexz
                writeJSON file: meta_json, json: meta
                shwrap("rm -f ${aws_image_filepath}")
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
            if (s3_stream_dir) {
              // Upload artifacts to AWS. Use the special utils
              // function for uploading the builds.json because it
              // handles concurrency (locking) for us.
              shwrap("""
              export AWS_CONFIG_FILE=\${AWS_FCOS_BUILDS_BOT_CONFIG}
              cosa buildupload --skip-builds-json \
                  s3 --acl=public-read ${s3_stream_dir}/builds
              """)
              pipeutils.bump_builds_json(
                  params.STREAM,
                  newBuildID,
                  basearch,
                  s3_stream_dir)
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
                    --build=${newBuildID} --arch=${basearch} \
                    --s3=${s3_stream_dir} --repo=compose \
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
                    string(name: 'ARCH', value: basearch),
                    string(name: 'FCOS_CONFIG_COMMIT', value: params.FCOS_CONFIG_COMMIT)
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
                    string(name: 'ARCH', value: basearch),
                    string(name: 'FCOS_CONFIG_COMMIT', value: params.FCOS_CONFIG_COMMIT)
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
                        -e VERSION=${newBuildID} \
                        -e ARCHES=${basearch} \
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
            def message = "[${params.STREAM}][${params.ARCH}] <${env.BUILD_URL}|${env.BUILD_NUMBER}>"

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
