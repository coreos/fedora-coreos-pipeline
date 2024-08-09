node {
    checkout scm
    // these are script global vars
    pipeutils = load("utils.groovy")
    pipecfg = pipeutils.load_pipecfg()
    gc_policy_data = pipeutils.load_gc()
    repo = "coreos/fedora-coreos-config"
}
// This job run the Garbage collection on the selected stream in accordance to the
// gc-policy.yaml available in https://github.com/coreos/fedora-coreos-pipeline. If
// the pruning step succeeds, it uploads the updated builds/builds.json to s3.
properties([
    pipelineTriggers([]),
    parameters([
        choice(name: 'STREAM',
               choices: pipeutils.get_streams_choices(pipecfg),
               description: 'CoreOS stream to run GC'),
        booleanParam(name: 'DRY_RUN',
                     defaultValue: false,
                     description: 'Dry run Garbage Collection')
    ])
])

def build_description = "[${params.STREAM}]"
def cosa_img = 'quay.io/coreos-assembler/coreos-assembler:main'
def container_env = pipeutils.get_env_vars_for_stream(pipecfg, params.STREAM)
def s3_stream_dir = pipeutils.get_s3_streams_dir(pipecfg, params.STREAM)
def dry_run = params.DRY_RUN ? "--dry-run" : ""

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
            def new_gc_policy_path = './gc-policy.yaml'
            writeYaml file: new_gc_policy_path, data: gc_policy_data

            stage('BuildFetch') {
                pipeutils.shwrapWithAWSBuildUploadCredentials("""
                cosa buildfetch --aws-config-file \${AWS_BUILD_UPLOAD_CONFIG} \
                    --stream ${params.STREAM} --url=s3://${s3_stream_dir}/builds
                """)
            }

            def credentials = [file(variable: 'GCP_KOLA_TESTS_CONFIG', credentialsId: 'gcp-image-upload-config')]
            withCredentials(credentials) {
                def gcp_project = shwrapCapture("jq -r .project_id \${GCP_KOLA_TESTS_CONFIG}")
                def acl = pipecfg.s3.acl ?: 'public-read'
                
                stage('Garbage Collection') {
                    pipeutils.shwrapWithAWSBuildUploadCredentials("""
                    cosa cloud-prune --policy ${new_gc_policy_path} \
                    --stream ${params.STREAM} ${dry_run} \
                    --gcp-project=\"${gcp_project}\" \
                    --gcp-json-key=\${GCP_KOLA_TESTS_CONFIG} \
                    --acl=${acl} \
                    --aws-config-file \${AWS_BUILD_UPLOAD_CONFIG}
                    """)
                }
            }

            // Nested lock for the Upload Builds JSON step
            lock(resource: "builds-json-${params.STREAM}") {
                    try {
                        stage('Upload Builds JSON') {
                            pipeutils.shwrapWithAWSBuildUploadCredentials("""
                            cosa cloud-prune --policy ${new_gc_policy_path} \
                            --stream ${params.STREAM} \
                            --upload-builds-json ${dry_run} \
                            --aws-config-file \${AWS_BUILD_UPLOAD_CONFIG}
                            """)
                        }
                    } catch (e) {
                        currentBuild.result = 'FAILURE'
                        throw e
                    }
            }

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
