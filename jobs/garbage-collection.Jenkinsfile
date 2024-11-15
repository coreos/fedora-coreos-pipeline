node {
    checkout scm
    // these are script global vars
    pipeutils = load("utils.groovy")
    pipecfg = pipeutils.load_pipecfg()
    gc_policy_data = pipeutils.load_gc()
    repo = "coreos/fedora-coreos-config"
}

// This job runs the Garbage collection on the selected stream in accordance with the
// gc-policy.yaml available in https://github.com/coreos/fedora-coreos-pipeline. If
// the pruning step succeeds, it uploads the updated builds/builds.json to s3.
properties([
    pipelineTriggers([]),
    parameters([
        choice(name: 'STREAM',
               choices: pipeutils.get_streams_choices(pipecfg) + ['branched', 'bodhi-updates'],
               description: 'CoreOS stream to run GC'),
        booleanParam(name: 'DRY_RUN',
                     defaultValue: true,
                     description: 'Only print what would be deleted')
    ]),
    buildDiscarder(logRotator(
        numToKeepStr: '200',
        artifactNumToKeepStr: '200'
    ))
])

def cosa_img = 'quay.io/coreos-assembler/coreos-assembler:main'
// def container_env = pipeutils.get_env_vars_for_stream(pipecfg, params.STREAM)
// Let's keep container_env as empty map temporarily to prune on disabled streams 
def container_env = [:]
def s3_stream_dir = pipeutils.get_s3_streams_dir(pipecfg, params.STREAM)
def dry_run = params.DRY_RUN ? "--dry-run" : ""
def build_description = "[${params.STREAM}] ${params.DRY_RUN ? '[dry-run]' : ''}"

lock(resource: "gc-${params.STREAM}") {
    cosaPod(image: cosa_img, env: container_env,
            memory: "1024Mi",
            serviceAccount: "jenkins") {
        try {
            currentBuild.description = "${build_description} Running"

            stage('Init') {
                def branch = params.STREAM
                sh "cosa init --branch \"${branch}\" https://github.com/${repo}"
            }

            // Write YAML data to a new file in cosaPod
            def new_gc_policy_path = 'tmp/gc-policy.yaml'
            writeYaml file: new_gc_policy_path, data: gc_policy_data

            stage('BuildFetch') {
                pipeutils.shwrapWithAWSBuildUploadCredentials("""
                cosa buildfetch --aws-config-file \${AWS_BUILD_UPLOAD_CONFIG} \
                    --stream ${params.STREAM} --url=s3://${s3_stream_dir}/builds
                """)
            }
            def originalBuildsJson = readJSON file: 'builds/builds.json'
            def originalTimestamp = originalBuildsJson.timestamp
            def acl = pipecfg.s3.acl ?: 'public-read'

            withCredentials([
                file(variable: 'GCP_KOLA_TESTS_CONFIG', credentialsId: 'gcp-image-upload-config'),
                file(variable: 'REGISTRY_SECRET', credentialsId: 'oscontainer-push-registry-secret'),
                file(variable: 'AWS_BUILD_UPLOAD_CONFIG', credentialsId: 'aws-build-upload-config')
            ]) {
                stage('Garbage Collection') {
                    shwrap("""
                    cosa coreos-prune --policy ${new_gc_policy_path} \
                    --stream ${params.STREAM} ${dry_run} \
                    --gcp-json-key=\${GCP_KOLA_TESTS_CONFIG} \
                    --acl=${acl} \
                    --registry-auth-file=\${REGISTRY_SECRET} \
                    --aws-config-file \${AWS_BUILD_UPLOAD_CONFIG}
                    """)
                }
            }

            def currentBuildsJson = readJSON file: 'builds/builds.json'
            def currentTimestamp = currentBuildsJson.timestamp

            // If the timestamp on builds.json after the 'Garbage Collection' step
            // is the same as before, that means, there were no resources to be pruned
            // and hence, no need to update the builds.json.
            if (originalTimestamp != currentTimestamp) {
                // Nested lock for the Upload Builds JSON step
                lock(resource: "builds-json-${params.STREAM}") {
                    stage('Upload Builds JSON') {
                        pipeutils.shwrapWithAWSBuildUploadCredentials("""
                        cosa coreos-prune --policy ${new_gc_policy_path} \
                        --stream ${params.STREAM} \
                        --upload-builds-json ${dry_run} \
                        --acl=${acl} \
                        --aws-config-file \${AWS_BUILD_UPLOAD_CONFIG}
                        """)
                    }
                }
            }
            currentBuild.result = 'SUCCESS'
            currentBuild.description = "${build_description} ✓"

        } catch (e) {
            currentBuild.result = 'FAILURE'
            throw e
        } finally {
            if (currentBuild.result != 'SUCCESS') {
                pipeutils.trySlackSend(message: ":wastebasket: garbage-collection #${env.BUILD_NUMBER} <${env.BUILD_URL}|:jenkins:> <${env.RUN_DISPLAY_URL}|:ocean:> [${params.STREAM}]")
            }
        }
    }
}
