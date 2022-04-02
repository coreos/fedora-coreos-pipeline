def pipeutils, streams, official
def azure_testing_resource_group
def azure_testing_storage_account
def azure_testing_storage_container
node {
    checkout scm
    pipeutils = load("utils.groovy")
    streams = load("streams.groovy")
    def pipecfg = pipeutils.load_config()
    azure_testing_resource_group = pipecfg['azure-testing-resource-group']
    azure_testing_storage_account = pipecfg['azure-testing-storage-account']
    azure_testing_storage_container = pipecfg['azure-testing-storage-container']
    official = pipeutils.isOfficial()
}

properties([
    pipelineTriggers([]),
    parameters([
      choice(name: 'STREAM',
             // list devel first so that it's the default choice
             choices: (streams.development + streams.production + streams.mechanical),
             description: 'Fedora CoreOS stream to test'),
      string(name: 'VERSION',
             description: 'Fedora CoreOS Build ID to test',
             defaultValue: '',
             trim: true),
      string(name: 'ARCH',
             description: 'Target architecture',
             defaultValue: 'x86_64',
             trim: true),
      string(name: 'S3_STREAM_DIR',
             description: 'Override the Fedora CoreOS S3 stream directory',
             defaultValue: '',
             trim: true),
      string(name: 'KOLA_TESTS',
             description: 'Override tests to run',
             defaultValue: "",
             trim: true),
      string(name: 'COREOS_ASSEMBLER_IMAGE',
             description: 'Override the coreos-assembler image to use',
             defaultValue: "coreos-assembler:main",
             trim: true),
      string(name: 'FCOS_CONFIG_COMMIT',
             description: 'The exact config repo git commit to run tests against',
             defaultValue: '',
             trim: true),
    ]),
    buildDiscarder(logRotator(
        numToKeepStr: '100',
        artifactNumToKeepStr: '100'
    )),
    durabilityHint('PERFORMANCE_OPTIMIZED')
])

// Locking so we don't run multiple times on the same region. We are speeding up our testing by dividing
// the load to different regions depending on the architecture. This allows us not to exceed our quota in 
// a single region while still being able to execute two test runs in parallel.
lock(resource: "kola-azure-${params.ARCH}") {

    currentBuild.description = "[${params.STREAM}][${params.ARCH}] - ${params.VERSION}"

    // Use eastus region for now
    def region = "eastus"

    def s3_stream_dir = params.S3_STREAM_DIR
    if (s3_stream_dir == "") {
        s3_stream_dir = "fcos-builds/prod/streams/${params.STREAM}"
    }

    try { timeout(time: 60, unit: 'MINUTES') {
        cosaPod(image: params.COREOS_ASSEMBLER_IMAGE,
                memory: "256Mi", kvm: false,
                secrets: ["aws-fcos-builds-bot-config", "azure-kola-tests-config"]) {

            def azure_image_name, azure_image_filepath
            stage('Fetch Metadata/Image') {
                def commitopt = ''
                if (params.FCOS_CONFIG_COMMIT != '') {
                    commitopt = "--commit=${params.FCOS_CONFIG_COMMIT}"
                }
                // Grab the metadata. Also grab the image so we can upload it.
                shwrap("""
                export AWS_CONFIG_FILE=\${AWS_FCOS_BUILDS_BOT_CONFIG}/config
                cosa init --branch ${params.STREAM} ${commitopt} https://github.com/coreos/fedora-coreos-config
                cosa buildfetch --build=${params.VERSION} --arch=${params.ARCH} \
                    --url=s3://${s3_stream_dir}/builds --artifact=azure
                cosa decompress --build=${params.VERSION} --artifact=azure
                """)
                azure_image_filepath = shwrapCapture("""
                cosa meta --build=${params.VERSION} --arch=${params.ARCH} --image-path azure
                """)

                // Use a consistent image name for this stream in case it gets left behind
                azure_image_name = "kola-fedora-coreos-${params.STREAM}-${params.ARCH}.vhd"
            }

            stage('Upload/Create Image') {
                // Create the image in Azure
                shwrap("""
                # First delete the blob/image since we re-use it.
                ore azure delete-image --log-level=INFO                           \
                    --azure-auth \${AZURE_KOLA_TESTS_CONFIG}/azureAuth.json       \
                    --azure-profile \${AZURE_KOLA_TESTS_CONFIG}/azureProfile.json \
                    --azure-location $region                                      \
                    --resource-group ${azure_testing_resource_group}              \
                    --image-name ${azure_image_name}
                ore azure delete-blob --log-level=INFO                            \
                    --azure-auth \${AZURE_KOLA_TESTS_CONFIG}/azureAuth.json       \
                    --azure-profile \${AZURE_KOLA_TESTS_CONFIG}/azureProfile.json \
                    --azure-location $region                                      \
                    --resource-group $azure_testing_resource_group                \
                    --storage-account $azure_testing_storage_account              \
                    --container $azure_testing_storage_container                  \
                    --blob-name $azure_image_name
                # Then create them fresh
                ore azure upload-blob --log-level=INFO                            \
                    --azure-auth \${AZURE_KOLA_TESTS_CONFIG}/azureAuth.json       \
                    --azure-profile \${AZURE_KOLA_TESTS_CONFIG}/azureProfile.json \
                    --azure-location $region                                      \
                    --resource-group $azure_testing_resource_group                \
                    --storage-account $azure_testing_storage_account              \
                    --container $azure_testing_storage_container                  \
                    --blob-name $azure_image_name                                 \
                    --file ${azure_image_filepath}
                ore azure create-image --log-level=INFO                           \
                    --azure-auth \${AZURE_KOLA_TESTS_CONFIG}/azureAuth.json       \
                    --azure-profile \${AZURE_KOLA_TESTS_CONFIG}/azureProfile.json \
                    --resource-group $azure_testing_resource_group                \
                    --azure-location $region                                      \
                    --image-name $azure_image_name                                \
                    --image-blob "https://${azure_testing_storage_account}.blob.core.windows.net/${azure_testing_storage_container}/${azure_image_name}"
                """)
            }
            
            // Since we don't have permanent images uploaded to Azure we'll
            // skip the upgrade test.
            try {
                def azure_subscription = shwrapCapture("jq -r .subscriptionId \${AZURE_KOLA_TESTS_CONFIG}/azureAuth.json")
                fcosKola(cosaDir: env.WORKSPACE, parallel: 10,
                        build: params.VERSION, arch: params.ARCH,
                        extraArgs: params.KOLA_TESTS,
                        skipBasicScenarios: true,
                        skipUpgrade: true,
                        platformArgs: """-p=azure                                         \
                            --azure-auth \${AZURE_KOLA_TESTS_CONFIG}/azureAuth.json       \
                            --azure-profile \${AZURE_KOLA_TESTS_CONFIG}/azureProfile.json \
                            --azure-location $region                                      \
                            --azure-disk-uri /subscriptions/${azure_subscription}/resourceGroups/${azure_testing_resource_group}/providers/Microsoft.Compute/images/${azure_image_name}""")
            } finally {
                stage('Delete Image') {
                    // Delete the image in Azure
                    shwrap("""
                    ore azure delete-image --log-level=INFO                           \
                        --azure-auth \${AZURE_KOLA_TESTS_CONFIG}/azureAuth.json       \
                        --azure-profile \${AZURE_KOLA_TESTS_CONFIG}/azureProfile.json \
                        --azure-location $region                                      \
                        --resource-group $azure_testing_resource_group                \
                        --image-name $azure_image_name
                    ore azure delete-blob --log-level=INFO                            \
                        --azure-auth \${AZURE_KOLA_TESTS_CONFIG}/azureAuth.json       \
                        --azure-profile \${AZURE_KOLA_TESTS_CONFIG}/azureProfile.json \
                        --azure-location $region                                      \
                        --resource-group $azure_testing_resource_group                \
                        --storage-account $azure_testing_storage_account              \
                        --container $azure_testing_storage_container                  \
                        --blob-name $azure_image_name
                    """)
                }
            }

            currentBuild.result = 'SUCCESS'
        }
    }} catch (e) {
        currentBuild.result = 'FAILURE'
        throw e
    } finally {
        if (official && currentBuild.result != 'SUCCESS') {
            slackSend(color: 'danger', message: ":fcos: :azure: :trashfire: kola-azure <${env.BUILD_URL}|#${env.BUILD_NUMBER}> [${params.STREAM}][${params.ARCH}] (${params.VERSION})")
        }
    }
}
