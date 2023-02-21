node {
    checkout scm
    // these are script global vars
    pipeutils = load("utils.groovy")
    pipecfg = pipeutils.load_pipecfg()
    libcloud = load("libcloud.groovy")
}

properties([
    pipelineTriggers([]),
    parameters([
      choice(name: 'STREAM',
             choices: pipeutils.get_streams_choices(pipecfg),
             description: 'CoreOS stream to release'),
      string(name: 'VERSION',
             description: 'CoreOS version to release',
             defaultValue: '',
             trim: true),
      string(name: 'ADDITIONAL_ARCHES',
             description: "Override additional architectures (space-separated). " +
                          "Use 'none' to only release for x86_64. " +
                          "Supported: ${pipeutils.get_supported_additional_arches().join(' ')}",
             defaultValue: "",
             trim: true),
      booleanParam(name: 'ALLOW_MISSING_ARCHES',
                   defaultValue: false,
                   description: 'Allow release to continue even with missing architectures'),
      // Default to true for CLOUD_REPLICATION because the only case
      // where we are running the job by hand is when we're doing a
      // production release and we want to replicate there. Defaulting
      // to true means there is less opportunity for human error.
      booleanParam(name: 'CLOUD_REPLICATION',
                   defaultValue: true,
                   description: 'Force cloud image replication'),
      string(name: 'COREOS_ASSEMBLER_IMAGE',
             description: 'Override coreos-assembler image to use',
             defaultValue: "",
             trim: true)
    ] + pipeutils.add_hotfix_parameters_if_supported()),
    durabilityHint('PERFORMANCE_OPTIMIZED')
])

def build_description = "[${params.STREAM}]"

// Reload pipecfg if a hotfix build was provided. The reason we do this here
// instead of loading the right one upfront is so that we don't modify the
// parameter definitions above and their default values.
if (params.PIPECFG_HOTFIX_REPO || params.PIPECFG_HOTFIX_REF) {
    node {
        pipecfg = pipeutils.load_pipecfg(params.PIPECFG_HOTFIX_REPO, params.PIPECFG_HOTFIX_REF)
        build_description = "[${params.STREAM}-${pipecfg.hotfix.name}]"
    }
}

// no way to make a parameter required directly so manually check
// https://issues.jenkins-ci.org/browse/JENKINS-3509
if (params.VERSION == "") {
    throw new Exception("Missing VERSION parameter!")
}

// runtime parameter always wins
def cosa_img = params.COREOS_ASSEMBLER_IMAGE
cosa_img = cosa_img ?: pipeutils.get_cosa_img(pipecfg, params.STREAM)
def basearches = []
if (params.ADDITIONAL_ARCHES != "none") {
    basearches = params.ADDITIONAL_ARCHES.split() as List
    basearches = basearches ?: pipeutils.get_additional_arches(pipecfg, params.STREAM)
}

// we always release for x86_64
basearches += 'x86_64'
// make sure there are no duplicates
basearches = basearches.unique()

def stream_info = pipecfg.streams[params.STREAM]

build_description += "[${basearches.join(' ')}][${params.VERSION}]"
currentBuild.description = "${build_description} Waiting"

// We just lock here out of an abundance of caution in case somehow two release
// jobs run for the same stream, but that really shouldn't happen. Anyway, if it
// *does*, this makes sure they're run serially.
// Also lock version-arch-specific locks to make sure these builds are finished.
def locks = basearches.collect{[resource: "release-${params.VERSION}-${it}"]}
lock(resource: "release-${params.STREAM}", extra: locks) {
    cosaPod(cpu: "1", memory: "1Gi", image: cosa_img,
            serviceAccount: "jenkins") {
    try {

        currentBuild.description = "${build_description} Running"

        def s3_stream_dir = pipeutils.get_s3_streams_dir(pipecfg, params.STREAM)
        def gcp_image = ""
        def ostree_prod_refs = [:]

        // Fetch metadata files for the build we are interested in
        stage('Fetch Metadata') {
            def ref = pipeutils.get_source_config_ref_for_stream(pipecfg, params.STREAM)
            def variant = stream_info.variant ? "--variant ${stream_info.variant}" : ""
            pipeutils.shwrapWithAWSBuildUploadCredentials("""
            cosa init --branch ${ref} ${variant} ${pipecfg.source_config.url}
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
                warn("Some requested architectures did not successfully build!")
                basearches = builtarches.intersect(basearches)
            } else {
                echo "ERROR: Some requested architectures did not successfully build"
                echo "ERROR: Detected built architectures: $builtarches"
                echo "ERROR: Requested base architectures: $basearches"
                currentBuild.result = 'FAILURE'
                return
            }
        }

        // Update description based on updated set of architectures
        build_description = "[${params.STREAM}][${basearches.join(' ')}][${params.VERSION}]"
        currentBuild.description = "${build_description} Running"

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


            if (params.CLOUD_REPLICATION) {
                libcloud.replicate_to_clouds(pipecfg, basearch, params.VERSION, params.STREAM)
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

        // XXX: hack: on releases that don't support pushing the
        // base-oscontainer, remove it from the list.
        def schema = "/usr/lib/coreos-assembler/v1.json"
        assert utils.pathExists(schema) : "cosa image missing ${schema}"
        if (shwrapRc("jq -e '.optional|contains([\"base-oscontainer\"])' ${schema}") != 0) {
            push_containers.remove('oscontainer')
        }

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
                        if (pipecfg.hotfix) {
                            // this is a hotfix build; include the hotfix name
                            // in the tag suffix so we don't clobber official
                            // tags
                            tag_suffix += "-hotfix-${pipecfg.hotfix.name}"
                        }
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
                def acl = pipecfg.s3.acl ?: 'public-read'
                shwrap("""
                cosa generate-release-meta --build-id ${params.VERSION} --workdir .
                cosa buildupload --build=${params.VERSION} --skip-builds-json \
                    ${arch_args} s3 --aws-config-file=\${AWS_BUILD_UPLOAD_CONFIG} \
                    --acl=${acl} ${s3_stream_dir}/builds
                """)

                def bucket_prefix = "${pipecfg.s3.bucket}/${pipecfg.s3.builds_key}"

                // make AMIs public if not already the case
                if (!pipecfg.clouds?.aws?.public) {
                    def rc = shwrapRc("""
                    cosa shell -- plume make-amis-public \
                        --version ${params.VERSION} \
                        --stream ${params.STREAM} \
                        --bucket-prefix ${bucket_prefix} \
                        --aws-credentials \${AWS_BUILD_UPLOAD_CONFIG}
                    """)
                    // see https://github.com/coreos/coreos-assembler/pull/3277
                    if (rc == 77) {
                        warn("Failed to make AMIs public in some regions")
                    } else if (rc != 0) {
                        error("plume make-amis-public exited with code ${rc}")
                    }
                }

                if (pipecfg.misc?.generate_release_index) {
                    shwrap("""
                    cosa shell -- plume update-release-index \
                        --version ${params.VERSION} \
                        --stream ${params.STREAM} \
                        --bucket-prefix ${bucket_prefix} \
                        --aws-credentials \${AWS_BUILD_UPLOAD_CONFIG}
                    """)
                }
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
        currentBuild.description = "${build_description} ✓"

// main try finishes here
} catch (e) {
    currentBuild.result = 'FAILURE'
    throw e
} finally {
    def stream = params.STREAM
    if (pipecfg.hotfix) {
        stream += "-${pipecfg.hotfix.name}"
    }
    pipeutils.trySlackSend(message: ":bullettrain_front: release <${env.BUILD_URL}|#${env.BUILD_NUMBER}> [${stream}][${basearches.join(' ')}] (${params.VERSION})")
}}} // try-catch-finally, cosaPod and lock finish here
