node {
    checkout scm
    // these are script global vars
    pipeutils = load("utils.groovy")
    pipecfg = pipeutils.load_pipecfg()
}

def brew_principal = pipecfg.brew.principal
def brew_profile = pipecfg.brew.profile

properties([
    pipelineTriggers([]),
    parameters([
      string(name: 'ADDITIONAL_ARCHES',
             description: "Override additional architectures (space-separated). " +
                          "Use 'none' to only upload for x86_64. " +
                          "Supported: ${pipeutils.get_supported_additional_arches().join(' ')}",
             defaultValue: "",
             trim: true),
      string(name: 'VERSION',
             description: 'Build version',
             defaultValue: '',
             trim: true),
      choice(name: 'STREAM',
             choices: pipeutils.get_streams_choices(pipecfg),
             description: 'CoreOS stream to upload to brew'),
      booleanParam(name: 'ALLOW_MISSING_ARCHES',
                   defaultValue: false,
                   description: 'Allow upload to continue even with missing architectures'),
    ]),
    buildDiscarder(logRotator(
        numToKeepStr: '100',
        artifactNumToKeepStr: '100'
    )),
    durabilityHint('PERFORMANCE_OPTIMIZED')
])

def build_description = "[${params.STREAM}]"

// runtime parameter always wins
def cosa_img = params.COREOS_ASSEMBLER_IMAGE
cosa_img = cosa_img ?: pipeutils.get_cosa_img(pipecfg, params.STREAM)
def basearches = []
if (params.ADDITIONAL_ARCHES != "none") {
    basearches = params.ADDITIONAL_ARCHES.split() as List
    basearches = basearches ?: pipeutils.get_additional_arches(pipecfg, params.STREAM)
}

// we always upload for x86_64
basearches += 'x86_64'
// make sure there are no duplicates
basearches = basearches.unique()
build_description += "[${basearches.join('')}][${params.VERSION}]"

// We don't need that much memory for downloading/uploading to brew, since
// it will be mostly metadata
def cosa_memory_request_mb = 1 * 1024 as Integer

// Same here, we don't need that much
def ncpus = 1

echo "Waiting for upload-brew lock"
currentBuild.description = "${build_description} Waiting"
def stream_info = pipecfg.streams[params.STREAM]

lock(resource: "upload-brew") {
    cosaPod(cpu: "${ncpus}",
            memory: "${cosa_memory_request_mb}Mi",
            image: cosa_img,
            serviceAccount: "jenkins",
            secrets: ["brew-keytab", "brew-ca:ca.crt:/etc/pki/ca.crt",
                      "koji-conf:koji.conf:/etc/koji.conf",
                      "krb5-conf:krb5.conf:/etc/krb5.conf"]) {
    timeout(time: 240, unit: 'MINUTES') {
    try {
        stage('Fetch Metadata') {
            def ref = pipeutils.get_source_config_ref_for_stream(pipecfg, params.STREAM)
            def variant = stream_info.variant ? "--variant ${stream_info.variant}" : ""
            def s3_stream_dir = pipeutils.get_s3_streams_dir(pipecfg, params.STREAM)
            pipeutils.shwrapWithAWSBuildUploadCredentials("""
                cosa init --branch ${ref} ${variant} ${pipecfg.source_config.url}
                cosa buildfetch --build=${params.VERSION} \
                    --arch=all --url=s3://${s3_stream_dir}/builds \
                    --aws-config-file \${AWS_BUILD_UPLOAD_CONFIG} \
                    --file "coreos-assembler-config-git.json"
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

        stage('Brew Upload') {
            def tag = pipecfg.streams[params.STREAM].brew.tag
            for (arch in basearches) {
                shwrap("""
                    coreos-assembler koji-upload \
                        upload --reserve-id \
                        --keytab "/run/kubernetes/secrets/brew-keytab/brew.keytab" \
                        --build ${params.VERSION} \
                        --retry-attempts 6 \
                        --buildroot builds \
                        --owner ${brew_principal} \
                        --profile ${brew_profile} \
                        --tag ${tag} \
                        --arch "$arch"
                """)
            }
        }
        currentBuild.result = 'SUCCESS'

    } catch (e) {
        currentBuild.result = 'FAILURE'
        throw e
    } finally {
        def message = "[${params.STREAM}][${basearches.join(' ')}] (${params.VERSION})"
        echo message
        if (currentBuild.result != 'SUCCESS') {
            pipeutils.trySlackSend(message: ":beer: brew-upload <${env.BUILD_URL}|#${env.BUILD_NUMBER}> ${message}")
        }
    }
}}} // timeout, cosaPod, and lock finish here
