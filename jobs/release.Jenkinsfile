def pipeutils, pipecfg, s3_bucket, official, src_config_url
node {
    checkout scm
    pipeutils = load("utils.groovy")
    pipecfg = pipeutils.load_pipecfg()
    pod = readFile(file: "manifests/pod.yaml")
    def jenkinscfg = pipeutils.load_jenkins_config()
    s3_bucket = pipecfg.s3_bucket
    src_config_url = pipecfg.source_config.url
    official = pipeutils.isOfficial()
}

properties([
    pipelineTriggers([]),
    parameters([
      choice(name: 'STREAM',
             choices: pipeutils.get_streams_choices(pipecfg),
             description: 'Fedora CoreOS stream to release'),
      string(name: 'VERSION',
             description: 'Fedora CoreOS version to release',
             defaultValue: '',
             trim: true),
      string(name: 'ARCHES',
             description: 'Space-separated list of target architectures',
             defaultValue: "x86_64" + " " + pipecfg.additional_arches.join(" "),
             trim: true),
      booleanParam(name: 'ALLOW_MISSING_ARCHES',
                   defaultValue: false,
                   description: 'Allow release to continue even with missing architectures'),
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

// single CPU should be enough for this job
pod = pod.replace("COREOS_ASSEMBLER_CPU_REQUEST", "1")
pod = pod.replace("COREOS_ASSEMBLER_CPU_LIMIT", "1")

echo "Final podspec: ${pod}"

// use a unique label to force Kubernetes to provision a separate pod per run
def pod_label = "cosa-${UUID.randomUUID().toString()}"

// Get the list of requested architectures to release
def basearches = params.ARCHES.split() as Set

def stream_info = pipecfg.streams[params.STREAM]

// We just lock here out of an abundance of caution in case somehow two release
// jobs run for the same stream, but that really shouldn't happen. Anyway, if it
// *does*, this makes sure they're run serially.
// Also lock version-arch-specific locks to make sure these builds are finished.
def locks = basearches.collect{[resource: "release-${params.VERSION}-${it}"]}
lock(resource: "release-${params.STREAM}", extra: locks) {
podTemplate(cloud: 'openshift', label: pod_label, yaml: pod) {
    node(pod_label) { container('coreos-assembler') { try {

        // print out details of the cosa image to help debugging
        shwrap("""
        cat /cosa/coreos-assembler-git.json
        """)

        def s3_stream_dir = "${s3_bucket}/prod/streams/${params.STREAM}"
        def gcp_image = ""
        def ostree_prod_refs = [:]

        // Fetch metadata files for the build we are interested in
        stage('Fetch Metadata') {
            withCredentials([file(variable: 'AWS_CONFIG_FILE',
                                  credentialsId: 'aws-build-upload-config')]) {
                shwrap("""
                cosa init --branch ${params.STREAM} ${src_config_url}
                cosa buildfetch --artifact=ostree --build=${params.VERSION} \
                    --arch=all --url=s3://${s3_stream_dir}/builds
                """)
            }
        }

        def builtarches = shwrapCapture("jq -r '.builds | map(select(.id == \"${params.VERSION}\"))[].arches[]' builds/builds.json").split() as Set
        assert builtarches.contains("x86_64"): "The x86_64 architecture was not in builtarches."
        if (!builtarches.containsAll(basearches)) {
            if (params.ALLOW_MISSING_ARCHES) {
                echo "Some requested architectures did not successfully build! Continuing."
                basearches = builtarches.intersect(basearches)
            } else {
                echo "ERROR: Some requested architectures did not successfully build"
                echo "ERROR: Detected built architectures: $builtarches"
                echo "ERROR: Requested base architectures: $basearches"
                currentBuild.result = 'FAILURE'
                return
            }
        }

        for (basearch in basearches) {
            def meta_json = "builds/${params.VERSION}/${basearch}/meta.json"
            def meta = readJSON file: meta_json


            // For production streams, import the OSTree into the prod
            // OSTree repo.
            if (stream_info.type == 'production') {
                pipeutils.tryWithMessagingCredentials() {
                    stage("OSTree Import ${basearch}: Prod Repo") {
                        shwrap("""
                        /usr/lib/coreos-assembler/fedmsg-send-ostree-import-request \
                            --build=${params.VERSION} --arch=${basearch} \
                            --s3=${s3_stream_dir} --repo=prod \
                            --fedmsg-conf=\${FEDORA_MESSAGING_CONF}
                        """)
                    }

                    ostree_prod_refs[meta.ref] = meta["ostree-commit"]
                }
            }

            // For production streams, promote the GCP image so that it
            // will be the chosen image in an image family and deprecate
            // all others. `ore gcloud promote-image` does this for us.
            if ((basearch == 'x86_64') && (meta.gcp?.image) &&
                    (stream_info.type == 'production')) {
                pipeutils.tryWithCredentials([file(variable: 'GCP_IMAGE_UPLOAD_CONFIG',
                                                   credentialsId: 'gcp-image-upload-config')]) {
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
            }


            if ((basearch in ['aarch64', 'x86_64']) && params.AWS_REPLICATION) {
                // Replicate AMI to other regions.
                stage("Replicate ${basearch} AWS AMI") {
                    withCredentials([file(variable: 'AWS_CONFIG_FILE',
                                          credentialsId: 'aws-build-upload-config')]) {
                        shwrap("""
                        cosa aws-replicate --build=${params.VERSION} --arch=${basearch} --log-level=INFO
                        """)
                    }
                }
            }
        }

        stage("Push OSContainer Manifest") {
            // Ship a manifest list containing all requested architectures.
            def oscontainer_registry_repo = pipecfg.registry_repos?.oscontainer
            if (oscontainer_registry_repo) {
                withCredentials([file(variable: 'REGISTRY_SECRET',
                                      credentialsId: 'oscontainer-push-registry-secret')]) {
                    def arch_args = basearches.collect{"--arch ${it}"}.join(" ")
                    shwrap("""
                    cosa push-container-manifest --auth=\${REGISTRY_SECRET} \
                        --repo=${oscontainer_registry_repo} --tag=${params.STREAM} \
                        --artifact=ostree --metajsonname=base-oscontainer \
                        --build=${params.VERSION} ${arch_args}
                    """)
                }
            }
            def oscontainer_old_registry_repo = pipecfg.registry_repos?.oscontainer_old
            if (oscontainer_old_registry_repo) {
                // For a period of time let's also mirror into the old location too
                // Drop this after October 2022. See
                // https://discussion.fedoraproject.org/t/updated-registry-location-for-fedora-coreos-ostree-native-container/42740
                withCredentials([file(credentialsId: 'oscontainer-secret', variable: 'REGISTRY_SECRET')]) {
                    shwrap("""
                    skopeo copy --all --authfile \$REGISTRY_SECRET \
                        docker://${oscontainer_registry_repo}:${params.STREAM} \
                        docker://${oscontainer_old_registry_repo}:${params.STREAM}
                    """)
                }
            }
        }

        stage('Publish') {
            withCredentials([file(variable: 'AWS_CONFIG_FILE',
                                  credentialsId: 'aws-build-upload-config')]) {
                // Since some of the earlier operations (like AWS replication) only modify
                // the individual meta.json files we need to re-generate the release metadata
                // to get the new info and upload it back to s3.
                def arch_args = basearches.collect{"--arch ${it}"}.join(" ")
                shwrap("""
                cosa generate-release-meta --build-id ${params.VERSION} --workdir .
                cosa buildupload --build=${params.VERSION} --skip-builds-json \
                    ${arch_args} s3 --acl=public-read ${s3_stream_dir}/builds
                """)

                // Run plume to publish official builds; This will handle modifying
                // object ACLs, modifying AMI image attributes,
                // and creating/modifying the releases.json metadata index
                shwrap("""
                plume release --distro fcos \
                    --version ${params.VERSION} \
                    --stream ${params.STREAM} \
                    --bucket ${s3_bucket}
                """)
            }

            pipeutils.tryWithMessagingCredentials() {
                for (basearch in basearches) {
                    shwrap("""
                    /usr/lib/coreos-assembler/fedmsg-broadcast --fedmsg-conf=\${FEDORA_MESSAGING_CONF} \
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
        if (official && currentBuild.result != 'SUCCESS') {
            slackSend(color: 'danger', message: ":fcos: :bullettrain_front: :trashfire: release <${env.BUILD_URL}|#${env.BUILD_NUMBER}> [${params.STREAM}][${params.ARCHES}] (${params.VERSION})")
        }
    }}}
}}
