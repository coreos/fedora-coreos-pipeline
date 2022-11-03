def pipeutils, pipecfg
node {
    checkout scm
    pipeutils = load("utils.groovy")
    pipecfg = pipeutils.load_pipecfg()
    def jenkinscfg = pipeutils.load_jenkins_config()
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
             defaultValue: "",
             trim: true)
    ]),
    durabilityHint('PERFORMANCE_OPTIMIZED')
])

// no way to make a parameter required directly so manually check
// https://issues.jenkins-ci.org/browse/JENKINS-3509
if (params.VERSION == "") {
    throw new Exception("Missing VERSION parameter!")
}

// runtime parameter always wins
def cosa_img = params.COREOS_ASSEMBLER_IMAGE
cosa_img = cosa_img ?: pipeutils.get_cosa_img(pipecfg, params.STREAM)

def stream_info = pipecfg.streams[params.STREAM]

currentBuild.description = "[${params.STREAM}][${params.ARCHES}] - ${params.VERSION}"

// Get the list of requested architectures to release
def basearches = params.ARCHES.split() as Set

// We just lock here out of an abundance of caution in case somehow two release
// jobs run for the same stream, but that really shouldn't happen. Anyway, if it
// *does*, this makes sure they're run serially.
// Also lock version-arch-specific locks to make sure these builds are finished.
def locks = basearches.collect{[resource: "release-${params.VERSION}-${it}"]}
lock(resource: "release-${params.STREAM}", extra: locks) {
    cosaPod(cpu: "1", memory: "512Mi", image: cosa_img) {
    try {

        def s3_stream_dir = pipeutils.get_s3_streams_dir(pipecfg, params.STREAM)
        def gcp_image = ""
        def ostree_prod_refs = [:]

        // Fetch metadata files for the build we are interested in
        stage('Fetch Metadata') {
            def ref = pipeutils.get_source_config_ref_for_stream(pipecfg, params.STREAM)
            pipeutils.shwrapWithAWSBuildUploadCredentials("""
            cosa init --branch ${ref} ${pipecfg.source_config.url}
            cosa buildfetch --build=${params.VERSION} \
                --arch=all --url=s3://${s3_stream_dir}/builds \
                --aws-config-file \${AWS_BUILD_UPLOAD_CONFIG}
            """)
        }

        def builtarches = shwrapCapture("""
                          cosa shell -- cat builds/builds.json | \
                              jq -r '.builds | map(select(.id == \"${params.VERSION}\"))[].arches[]'
                          """).split() as Set
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

        // Fetch Artifact files for pieces we still need to upload. Note that we
        // need to do this early in this job before we've run any stages that
        // modify/update meta.json because buildfetch will re-download and
        // overwrite meta.json.
        stage('Fetch Artifacts') {
            // We need to fetch a few artifacts if they were built. This assumes if
            // it was built for one platform it was built for all.
            def fetch_artifacts = ['ostree', 'extensions-container', 'legacy-oscontainer']
            def meta = readJSON(text: shwrapCapture("cosa meta --build=${params.VERSION} --arch=x86_64 --dump"))
            fetch_artifacts.retainAll(meta.images.keySet())

            def fetch_args = basearches.collect{"--arch=${it}"}
            fetch_args += fetch_artifacts.collect{"--artifact=${it}"}

            pipeutils.shwrapWithAWSBuildUploadCredentials("""
            cosa buildfetch --build=${params.VERSION} \
                --url=s3://${s3_stream_dir}/builds    \
                --aws-config-file \${AWS_BUILD_UPLOAD_CONFIG} \
                ${fetch_args.join(' ')}
            """)
        }

        for (basearch in basearches) {
            def meta = readJSON(text: shwrapCapture("cosa meta --build=${params.VERSION} --arch=${basearch} --dump"))

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

            // For production streams, if the GCP image is in an image family
            // then promote the GCP image so that it will be the chosen image
            // in the image family and deprecate all others.
            // `ore gcloud promote-image` does this for us.
            if ((basearch == 'x86_64') && (meta.gcp?.image) &&
                (meta.gcp?.family) && (stream_info.type == 'production')) {
                tryWithCredentials([file(variable: 'GCP_IMAGE_UPLOAD_CONFIG',
                                         credentialsId: 'gcp-image-upload-config')]) {
                    stage("GCP ${basearch}: Image Promotion") {
                        shwrap("""
                        ore gcloud promote-image \
                            --log-level=INFO \
                            --project=${meta.gcp.project} \
                            --json-key \${GCP_IMAGE_UPLOAD_CONFIG} \
                            --family=${meta.gcp.family} \
                            --image=${meta.gcp.image}
                        """)
                    }
                }
            }


            if (meta.amis && params.AWS_REPLICATION) {
                // Replicate AMI to other regions.
                stage("${basearch} AWS AMI Replication") {
                    def replicated = false
                    def ids = ['aws-build-upload-config', 'aws-govcloud-image-upload-config']
                    for (id in ids) {
                        tryWithCredentials([file(variable: 'AWS_CONFIG_FILE', credentialsId: id)]) {
                            shwrap("""
                            cosa aws-replicate --build=${params.VERSION} --arch=${basearch} --log-level=INFO
                            """)
                            replicated = true
                        }
                    }
                    if (!replicated) {
                        error("AWS Replication asked for but no credentials exist")
                    }
                }
            }
        }

        def registry_repos = pipeutils.get_registry_repos(pipecfg, params.STREAM)

        // [config.yaml name -> [meta.json artifact name, meta.json toplevel name, tag suffix]]
        // The config.yaml name is the name used in the `registry_repos` object. The
        // meta.json artifact name is the "cosa name" for the artifact (in the `images`
        // object). The top-level name is the key inserted with the pushed info. The tag
        // suffix is needed to avoid the containers clashing in the OCP ART monorepo. It
        // could be made configurable in the future. For now since FCOS doesn't need it and
        // OCP ART doesn't actually care what the tag name is (it's just to stop GC), we
        // hardcode it.
        def push_containers = ['oscontainer': ['ostree', 'base-oscontainer', ''],
                               'extensions': ['extensions-container', 'extensions-container', '-extensions'],
                               'legacy_oscontainer': ['legacy-oscontainer', 'oscontainer', '-legacy']]

        // filter out those not defined in the config
        push_containers.keySet().retainAll(registry_repos.keySet())

        // filter out those not built. this step makes the assumption that if an
        // image isn't built for x86_64, then we don't upload it at all
        def meta_x86_64 = readJSON(text: shwrapCapture("cosa meta --build=${params.VERSION} --arch=x86_64 --dump"))
        def artifacts = meta_x86_64.images.keySet()

        // in newer Groovy, retainAll can take a closure, which would be nicer here
        for (key in (push_containers.keySet() as List)) {
            if (!(push_containers[key][0] in artifacts)) {
                push_containers.remove(key)
            }
        }

        if (push_containers) {
            stage("Push Containers") {
                parallel push_containers.collectEntries{configname, val -> [configname, {
                    withCredentials([file(variable: 'REGISTRY_SECRET',
                                          credentialsId: 'oscontainer-push-registry-secret')]) {
                        def repo = registry_repos[configname]
                        def (artifact, metajsonname, tag_suffix) = val
                        def extra_args = basearches.collect{"--arch ${it}"}
                        if (registry_repos.v2s2) {
                            extra_args += "--v2s2"
                        }
                        def tag_args = ["--tag=${params.STREAM}${tag_suffix}"]
                        if (registry_repos.add_build_tag) {
                            tag_args += "--tag=${params.VERSION}${tag_suffix}"
                        }
                        shwrap("""
                        export STORAGE_DRIVER=vfs # https://github.com/coreos/fedora-coreos-pipeline/issues/723#issuecomment-1297668507
                        cosa push-container-manifest --auth=\${REGISTRY_SECRET} \
                            --repo=${repo} ${tag_args.join(' ')} \
                            --artifact=${artifact} --metajsonname=${metajsonname} \
                            --build=${params.VERSION} ${extra_args.join(' ')}
                        """)

                        def old_repo = registry_repos["${configname}_old"]
                        if (old_repo) {
                            // a separate credential for the old location is optional; we support it
                            // being merged as part of oscontainer-push-registry-secret
                            pipeutils.tryWithOrWithoutCredentials([file(variable: 'OLD_REGISTRY_SECRET',
                                                                        credentialsId: 'oscontainer-push-old-registry-secret')]) {
                                def authArg = "--authfile=\${REGISTRY_SECRET}"
                                if (env.OLD_REGISTRY_SECRET) {
                                    authArg += " --dest-authfile=\${OLD_REGISTRY_SECRET}"
                                }
                                shwrap("""
                                export STORAGE_DRIVER=vfs # https://github.com/coreos/fedora-coreos-pipeline/issues/723#issuecomment-1297668507
                                cosa copy-container ${authArg} ${tag_args.join(' ')} \
                                    --manifest-list-to-arch-tag=auto \
                                    ${repo} ${old_repo}
                                """)
                            }
                        }
                    }
                }]}
            }
        }

        stage('Publish') {
            pipeutils.withAWSBuildUploadCredentials() {
                // Since some of the earlier operations (like AWS replication) only modify
                // the individual meta.json files we need to re-generate the release metadata
                // to get the new info and upload it back to s3.
                def arch_args = basearches.collect{"--arch ${it}"}.join(" ")
                shwrap("""
                cosa generate-release-meta --build-id ${params.VERSION} --workdir .
                cosa buildupload --build=${params.VERSION} --skip-builds-json \
                    ${arch_args} s3 --aws-config-file=\${AWS_BUILD_UPLOAD_CONFIG} \
                    --acl=public-read ${s3_stream_dir}/builds
                """)

                // Run plume to publish official builds; This will handle modifying
                // object ACLs, modifying AMI image attributes,
                // and creating/modifying the releases.json metadata index
                // Note here we pass the bucket instead of `s3_stream_dir` but
                // plume currently only actually interacts with objects under
                // the `s3_stream_dir` key (i.e. `pipecfg.s3.builds_key`).
                // We'll make this more explicit in the future.
                shwrap("""
                cosa shell -- plume release --distro fcos \
                    --version ${params.VERSION} \
                    --stream ${params.STREAM} \
                    --bucket ${pipecfg.s3.bucket} \
                    --aws-credentials \${AWS_BUILD_UPLOAD_CONFIG}
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

// main try finishes here
} catch (e) {
    currentBuild.result = 'FAILURE'
    throw e
} finally {
    if (currentBuild.result != 'SUCCESS') {
        pipeutils.trySlackSend(color: 'danger', message: ":fcos: :bullettrain_front: :trashfire: release <${env.BUILD_URL}|#${env.BUILD_NUMBER}> [${params.STREAM}][${params.ARCHES}] (${params.VERSION})")
    }
}}} // try-catch-finally, cosaPod and lock finish here
