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
             description: 'CoreOS stream to sign'),
      string(name: 'VERSION',
             description: 'CoreOS version to sign',
             defaultValue: '',
             trim: true),
      string(name: 'ARCHES',
             description: "Override architectures (space-separated) to sign. " +
                          "Defaults to all arches for this stream.",
             defaultValue: "",
             trim: true),
      booleanParam(name: 'SKIP_SIGNING',
                   defaultValue: false,
                   description: 'Skip signing artifacts'),
      booleanParam(name: 'SKIP_COMPOSE_IMPORT',
                   defaultValue: false,
                   description: 'Skip requesting compose repo import'),
      booleanParam(name: 'SKIP_TESTS',
                   defaultValue: false,
                   description: 'Skip triggering cloud and upgrade tests'),
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

build_description = "${build_description}[$params.VERSION]"

// runtime parameter always wins
def cosa_img = params.COREOS_ASSEMBLER_IMAGE
cosa_img = cosa_img ?: pipeutils.get_cosa_img(pipecfg, params.STREAM)

def basearches = []
if (params.ARCHES != "") {
    basearches = params.ARCHES.split() as List
} else {
    basearches += 'x86_64'
    basearches += pipeutils.get_additional_arches(pipecfg, params.STREAM)
}

// make sure there are no duplicates
basearches = basearches.unique()

def stream_info = pipecfg.streams[params.STREAM]

currentBuild.description = "${build_description} Waiting"

// We just lock here out of an abundance of caution in case somehow
// two jobs run for the same stream, but that really shouldn't happen.
// Also lock version-arch-specific locks to make sure these builds are finished.
lock(resource: "sign-${params.VERSION}") {
    cosaPod(cpu: "1", memory: "512Mi", image: cosa_img,
            serviceAccount: "jenkins") {
    try {
        currentBuild.description = "${build_description} Running"

        def s3_stream_dir = pipeutils.get_s3_streams_dir(pipecfg, params.STREAM)

        // Fetch metadata files for the build we are interested in
        stage('Fetch Metadata') {
            def ref = pipeutils.get_source_config_ref_for_stream(pipecfg, params.STREAM)
            def variant = stream_info.variant ? "--variant ${stream_info.variant}" : ""
            def arch_args = basearches.collect{"--arch=$it"}
            pipeutils.shwrapWithAWSBuildUploadCredentials("""
            cosa init --branch ${ref} ${variant} ${pipecfg.source_config.url}
            cosa buildfetch --build=${params.VERSION} \
                ${arch_args.join(' ')} --artifact=all --url=s3://${s3_stream_dir}/builds \
                --aws-config-file \${AWS_BUILD_UPLOAD_CONFIG}
            """)
        }

        def builtarches = shwrapCapture("""
                          cosa shell -- cat builds/builds.json | \
                              jq -r '.builds | map(select(.id == \"${params.VERSION}\"))[].arches[]'
                          """).split() as Set
        assert builtarches.contains("x86_64"): "The x86_64 architecture was not in builtarches."
        if (!builtarches.containsAll(basearches)) {
            echo "ERROR: Some requested architectures did not successfully build"
            echo "ERROR: Detected built architectures: $builtarches"
            echo "ERROR: Requested base architectures: $basearches"
            currentBuild.result = 'FAILURE'
            return
        }

        // Update description based on updated set of architectures
        build_description = "[${params.STREAM}][${basearches.join(' ')}][${params.VERSION}]"
        currentBuild.description = "${build_description} Running"

        def src_config_commit = shwrapCapture("""
            jq '.[\"coreos-assembler.config-gitrev\"]' builds/${params.VERSION}/${basearches[0]}/meta.json
        """)

        for (basearch in basearches) {
            pipeutils.tryWithMessagingCredentials() {
                def parallelruns = [:]
                if (!params.SKIP_SIGNING) {
                    parallelruns['Sign Images'] = {
                        pipeutils.signImages(params.STREAM, params.VERSION, basearch, s3_stream_dir)
                    }
                } else {
                    // If we skipped signing, just at least validate them
                    // and make sure they have public ACLs.
                    parallelruns['Verify Image Signatures'] = {
                        pipeutils.signImages(params.STREAM, params.VERSION, basearch, s3_stream_dir, true)
                    }
                }
                if (!params.SKIP_COMPOSE_IMPORT) {
                    parallelruns['OSTree Import: Compose Repo'] = {
                        pipeutils.composeRepoImport(params.VERSION, basearch, s3_stream_dir)
                    }
                }
                // process this batch
                parallel parallelruns
            }

            if (!params.SKIP_TESTS) {
                stage('Cloud Tests') {
                    pipeutils.run_cloud_tests(pipecfg, params.STREAM, params.VERSION,
                                              cosa_img, basearch, src_config_commit)
                }
                if (pipecfg.misc?.run_extended_upgrade_test_fcos) {
                    stage('Upgrade Tests') {
                        pipeutils.run_fcos_upgrade_tests(pipecfg, params.STREAM, params.VERSION,
                                                         cosa_img, basearch, src_config_commit)
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
    pipeutils.trySlackSend(message: ":hammer: fix-build #${env.BUILD_NUMBER} <${env.BUILD_URL}|:jenkins:> <${env.RUN_DISPLAY_URL}|:ocean:> [${params.STREAM}][${basearches.join(' ')}] (${params.VERSION})")
}}} // try-catch-finally, cosaPod and lock finish here
