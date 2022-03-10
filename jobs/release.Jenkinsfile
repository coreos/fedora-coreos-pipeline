def pipeutils, streams, s3_bucket
node {
    checkout scm
    pipeutils = load("utils.groovy")
    streams = load("streams.groovy")
    pod = readFile(file: "manifests/pod.yaml")

    s3_bucket = pipeutils.get_config('s3-bucket')
}

properties([
    pipelineTriggers([]),
    parameters([
      choice(name: 'STREAM',
             // list devel first so that it's the default choice
             choices: (streams.development + streams.production + streams.mechanical),
             description: 'Fedora CoreOS stream to release'),
      string(name: 'VERSION',
             description: 'Fedora CoreOS version to release',
             defaultValue: '',
             trim: true),
      string(name: 'ARCHES',
             description: 'Space-separated list of target architectures',
             defaultValue: 'x86_64 aarch64',
             trim: true),
      // Default to true for AWS_REPLICATION because the only case
      // where we are running the job by hand is when we're doing a
      // production release and we want to replicate there. Defaulting
      // to true means there is less opportunity for human error.
      booleanParam(name: 'AWS_REPLICATION',
                   defaultValue: true,
                   description: 'Force AWS AMI replication'),
      string(name: 'COREOS_ASSEMBLER_IMAGE',
             description: 'Override coreos-assembler image to use',
             defaultValue: "coreos-assembler:main",
             trim: true)
    ]),
    durabilityHint('PERFORMANCE_OPTIMIZED')
])

// no way to make a parameter required directly so manually check
// https://issues.jenkins-ci.org/browse/JENKINS-3509
if (params.VERSION == "") {
    throw new Exception("Missing VERSION parameter!")
}

currentBuild.description = "[${params.STREAM}][${params.ARCHES}] - ${params.VERSION}"

// substitute the right COSA image into the pod definition before spawning it
pod = pod.replace("COREOS_ASSEMBLER_IMAGE", params.COREOS_ASSEMBLER_IMAGE)

// shouldn't need more than 256Mi for this job
pod = pod.replace("COREOS_ASSEMBLER_MEMORY_REQUEST", "256Mi")

echo "Final podspec: ${pod}"

// use a unique label to force Kubernetes to provision a separate pod per run
def pod_label = "cosa-${UUID.randomUUID().toString()}"

// Destination for OCI image push
// TODO: Change this to quay.io/fedora/coreos per https://fedoraproject.org/wiki/Changes/OstreeNativeContainer
def quay_registry = "quay.io/coreos-assembler/fcos"

// We just lock here out of an abundance of caution in case somehow two release
// jobs run for the same stream, but that really shouldn't happen. Anyway, if it
// *does*, this makes sure they're run serially.
lock(resource: "release-${params.STREAM}") {
podTemplate(cloud: 'openshift', label: pod_label, yaml: pod) {
    node(pod_label) { container('coreos-assembler') { try {

        // print out details of the cosa image to help debugging
        shwrap("""
        cat /cosa/coreos-assembler-git.json
        """)

        def s3_stream_dir = "${s3_bucket}/prod/streams/${params.STREAM}"
        def gcp_image = ""
        def ostree_prod_refs = [:]

        // Clone the automation repo, which contains helper scripts. In the
        // future, we'll probably want this either part of the cosa image, or
        // in a derivative of cosa for pipeline needs.
        shwrap("""
        git clone --depth=1 https://github.com/coreos/fedora-coreos-releng-automation /var/tmp/fcos-releng
        """)

        // Fetch metadata files for the build we are interested in
        stage('Fetch Metadata') {
            shwrap("""
            export AWS_CONFIG_FILE=\${AWS_FCOS_BUILDS_BOT_CONFIG}
            cosa init --branch ${params.STREAM} https://github.com/coreos/fedora-coreos-config
            cosa buildfetch --artifact=ostree --build=${params.VERSION} \
                --arch=all --url=s3://${s3_stream_dir}/builds
            """)
        }

        def basearches = params.ARCHES.split()

        for (basearch in basearches) {
            def meta_json = "builds/${params.VERSION}/${basearch}/meta.json"
            def meta = readJSON file: meta_json

            // for now we only support pushing x86_64 images
            if (basearch == 'x86_64') {
                stage("Push Container") {
                    def image_path = shwrapCapture("cosa meta --build=${params.VERSION} --image-path ostree")
                    withCredentials([file(credentialsId: 'oscontainer-secret', variable: 'OSCONTAINER_SECRET')]) {
                        withEnv(["SRC_IMAGE=${image_path}",
                                 "DEST_IMAGE=docker://${quay_registry}:${params.STREAM}"]) {
                            sh('skopeo copy --authfile=${OSCONTAINER_SECRET} oci-archive://${SRC_IMAGE} ${DEST_IMAGE}')
                        }
                    }
                }
            }

            // For production streams, import the OSTree into the prod
            // OSTree repo.
            if ((params.STREAM in streams.production) && utils.pathExists("/etc/fedora-messaging-cfg/fedmsg.toml")) {
                stage("OSTree Import ${basearch}: Prod Repo") {
                    shwrap("""
                    /var/tmp/fcos-releng/coreos-ostree-importer/send-ostree-import-request.py \
                        --build=${params.VERSION} --arch=${basearch} \
                        --s3=${s3_stream_dir} --repo=prod \
                        --fedmsg-conf=/etc/fedora-messaging-cfg/fedmsg.toml
                    """)
                }

                ostree_prod_refs[meta.ref] = meta["ostree-commit"]
            }

            // For production streams, promote the GCP image so that it
            // will be the chosen image in an image family and deprecate
            // all others. `ore gcloud promote-image` does this for us.
            if ((basearch == 'x86_64') && (meta.gcp?.image) &&
                    (params.STREAM in streams.production)) {
                stage("GCP ${basearch}: Image Promotion") {
                    shwrap("""
                    # pick up the project to use from the config
                    gcp_project=\$(jq -r .project_id \${GCP_IMAGE_UPLOAD_CONFIG})
                    ore gcloud promote-image \
                        --log-level=INFO \
                        --project=\${gcp_project} \
                        --json-key \${GCP_IMAGE_UPLOAD_CONFIG} \
                        --family fedora-coreos-${params.STREAM} \
                        --image "${meta.gcp.image}"
                    """)
                }
            }


            if ((basearch in ['aarch64', 'x86_64']) && params.AWS_REPLICATION) {
                // Replicate AMI to other regions.
                stage("Replicate ${basearch} AWS AMI") {
                    shwrap("""
                    export AWS_CONFIG_FILE=\${AWS_FCOS_BUILDS_BOT_CONFIG}
                    cosa aws-replicate --build=${params.VERSION} --arch=${basearch} --log-level=INFO
                    """)
                }
            }
        }

        stage('Publish') {
            // Since some of the earlier operations (like AWS replication) only modify
            // the individual meta.json files we need to re-generate the release metadata
            // to get the new info and upload it back to s3.
            def arch_args = basearches.collect{"--arch ${it}"}.join(" ")
            shwrap("""
            export AWS_CONFIG_FILE=\${AWS_FCOS_BUILDS_BOT_CONFIG}
            cosa generate-release-meta --build-id ${params.VERSION} --workdir .
            cosa buildupload --build=${params.VERSION} --skip-builds-json \
                ${arch_args} s3 --acl=public-read ${s3_stream_dir}/builds
            """)

            // Run plume to publish official builds; This will handle modifying
            // object ACLs, modifying AMI image attributes,
            // and creating/modifying the releases.json metadata index
            shwrap("""
            export AWS_CONFIG_FILE=\${AWS_FCOS_BUILDS_BOT_CONFIG}
            plume release --distro fcos \
                --version ${params.VERSION} \
                --stream ${params.STREAM} \
                --bucket ${s3_bucket}
            """)

            if (utils.pathExists("/etc/fedora-messaging-cfg/fedmsg.toml")) {
                for (basearch in basearches) {
                    shwrap("""
                    /var/tmp/fcos-releng/scripts/broadcast-fedmsg.py --fedmsg-conf=/etc/fedora-messaging-cfg/fedmsg.toml \
                        stream.release --build ${params.VERSION} --basearch ${basearch} --stream ${params.STREAM}
                    """)
                }
            }
        }

        if (ostree_prod_refs.size() > 0) {
            stage("OSTree Import: Wait and Verify") {
                def tmpd = shwrapCapture("mktemp -d")

                shwrap("""
                cd ${tmpd}
                ostree init --mode=archive --repo=.
                # add official repo config, which enforces signature checking
                cat /etc/ostree/remotes.d/fedora.conf >> config
                """)

                // We do this in a loop because it takes time for the import to
                // complete and the updated summary file to propagate. But it
                // shouldn't normally take more than 20 minutes.
                timeout(time: 20, unit: 'MINUTES') {
                    for (ref in ostree_prod_refs) {
                        shwrap("""
                        cd ${tmpd}
                        while true; do
                            ostree pull --commit-metadata-only fedora:${ref.key}
                            chksum=\$(ostree rev-parse fedora:${ref.key})
                            if [ "\${chksum}" == "${ref.value}" ]; then
                                break
                            fi
                            sleep 30
                        done
                        """)
                    }
                }
            }
        }
        currentBuild.result = 'SUCCESS'
    } catch (e) {
        currentBuild.result = 'FAILURE'
        throw e
    } finally {
        if (currentBuild.result != 'SUCCESS') {
            slackSend(color: 'danger', message: ":fcos: :bullettrain_front: :trashfire: release <${env.BUILD_URL}|#${env.BUILD_NUMBER}> [${params.STREAM}][${params.ARCHES}] (${params.VERSION})")
        }
    }}}
}}
