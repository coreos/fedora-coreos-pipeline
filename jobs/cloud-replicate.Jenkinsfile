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
                          "Use 'none' to only replicate for x86_64. " +
                          "Supported: ${pipeutils.get_supported_additional_arches().join(' ')}",
             defaultValue: "",
             trim: true),
      booleanParam(name: 'ALLOW_MISSING_ARCHES',
                   defaultValue: false,
                   description: 'Allow release to continue even with missing architectures'),
      string(name: 'COREOS_ASSEMBLER_IMAGE',
             description: 'Override coreos-assembler image to use',
             defaultValue: "",
             trim: true)
    ] + pipeutils.add_hotfix_parameters_if_supported()),
    buildDiscarder(logRotator(
        numToKeepStr: '100',
        artifactNumToKeepStr: '100'
    )),
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

// We just lock here out of an abundance of caution in case somehow
// two jobs run for the same stream, but that really shouldn't happen.
// Also lock version-arch-specific locks to make sure these builds are finished.
lock(resource: "cloud-replicate-${params.VERSION}") {
    cosaPod(cpu: "1", memory: "512Mi", image: cosa_img,
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

        // Update description based on updated set of architectures
        build_description = "[${params.STREAM}][${basearches.join(' ')}][${params.VERSION}]"
        currentBuild.description = "${build_description} Running"

        for (basearch in basearches) {
            libcloud.replicate_to_clouds(pipecfg, basearch, params.VERSION, params.STREAM)
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
            }
        }

        currentBuild.result = 'SUCCESS'
        currentBuild.description = "${build_description} ✓"

// main try finishes here
} catch (e) {
    currentBuild.result = 'FAILURE'
    throw e
} finally {
    pipeutils.trySlackSend(message: ":cloud: :arrows_counterclockwise: cloud-replicate #${env.BUILD_NUMBER} <${env.BUILD_URL}|:jenkins:> <${env.RUN_DISPLAY_URL}|:ocean:> [${params.STREAM}][${basearches.join(' ')}] (${params.VERSION})")
}}} // try-catch-finally, cosaPod and lock finish here
