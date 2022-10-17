
// Upload artifacts to clouds
def upload_to_clouds(pipecfg, basearch, buildID, stream) {

    // Get artifacts to upload, if we have an artifact built
    // try to uplaoad it
    def meta_json = "builds/${buildID}/${basearch}/meta.json"
    def meta = readJSON file: meta_json
    def artifacts = meta['images'].keySet() as List
    def uploaders = [:]

    artifacts.each {

        def bucket = pipecfg.clouds."${it}".bucket
        def path = pipecfg.clouds."${it}".path
        def primary_region = pipecfg.clouds."${it}".primary_region

        if (it == 'aws') {
            uploaders["aws"] = {
                tryWithCredentials([file(variable: "AWS_BUILD_UPLOAD_CONFIG",
                                   credentialsId: "aws-build-upload-config")]) {
                    def grant_user_args = \
                        pipecfg.clouds?.aws?.test_accounts.collect{"--grant-user ${it}"}.join(" ")
                    bucket = "s3://${bucket}/$path"
                    shwrap("""
                    cosa buildextend-aws \
                        --upload \
                        --arch=${basearch} \
                        --build=${buildID} \
                        --region=${primary_region} ${grant_user_args} \
                        --bucket ${bucket} \
                        --credentials-file=\${AWS_BUILD_UPLOAD_CONFIG}
                    """)
                }
            }
        }
        if (it == 'aliyun') {
             uploaders["aliyun"] = {
                 tryWithCredentials([file(variable: "ALIYUN_IMAGE_UPLOAD_CONFIG",
                                    credentialsId: "aliyun-image-upload-config")]) {
                    shwrap("""
                    cosa buildextend-aliyun \
                           --upload \
                           --arch=${basearch} \
                           --build=${buildID} \
                           --region=${primary_region} \
                           --bucket ${bucket} \
                           --credentials-file=\${ALIYUN_IMAGE_UPLOAD_CONFIG}
                       """)
                }
            }
        }
        // For azure we are using two files for RHCOS secret: azure.json and azureProfile.json
        // We need to investigate if we can have a single secret (token) to upload it,
        // making it similar to FCOS
        if (it == 'azure') {
            uploaders["azure"] = {
                tryWithCredentials([file(variable: "AZURE_IMAGE_UPLOAD_CONFIG",
                                    credentialsId: "azure-image-upload-config")]) {
                    def container = pipecfg.clouds.azure.container
                    def resource_group = pipecfg.clouds.azure.resource_group
                    def storage_account = pipecfg.clouds.azure.storage_account
                    shwrap("""cosa buildextend-azure \
                        --upload \
                        --auth \${AZURE_IMAGE_UPLOAD_CONFIG}/azure.json \
                        --build=${buildID} \
                        --container ${container} \
                        --profile \${AZURE_IMAGE_UPLOAD_CONFIG}/azureProfile.json \
                        --resource-group ${resource_group} \
                        --storage-account ${storage_account} \
                        --force
                     """)
                }
            }
        }
        if (it == 'gcp') {
            uploaders["gcp"] = {
                tryWithCredentials([file(variable: "GCP_IMAGE_UPLOAD_CONFIG",
                                   credentialsId: "gcp-image-upload-config")]) {
                    def create_image =  (pipecfg.clouds.gcp.create_image ? "--create-image=true": "")
                    def description =  (pipecfg.clouds.gcp.description ? ('--description=' + pipecfg.clouds.gcp.description): "")
                    def family =  (pipecfg.clouds.gcp.family.image_family ? '--family ' + pipecfg.clouds.gcp.family.image_family + '--deprecated': "")
                    def acl = (pipecfg.clouds.gcp.public ? "--public" : "")
                    def gcp_licenses = pipecfg.clouds.gcp.licenses
                    def licenses = gcp_licenses.collect{"--license " + it.replace('STREAM', stream)}.join(" ")
                    def today = shwrapCapture("date +%Y-%m-%d")
                    family = family.replace("STREAM", stream)
                    description = description.replace("BUILDID", buildID)
                    description = description.replace("STREAM", stream)
                    description = description.replace("BASEARCH", basearch)
                    description = description.replace("TODAY", today)
                    shwrap("""
                    # pick up the project to use from the config
                    gcp_project=\$(jq -r .project_id \${GCP_IMAGE_UPLOAD_CONFIG})
                    # NOTE: Add --deprecated to create image in deprecated state.
                    #       We undeprecate in the release pipeline with promote-image.
                    cosa buildextend-gcp \
                        --log-level=INFO \
                        --build=${buildID} \
                        --upload \
                        --project=\${gcp_project} \
                        --bucket gs://${bucket}/${path} \
                        --json \${GCP_IMAGE_UPLOAD_CONFIG} \
                        ${create_image} ${description} ${family} ${licenses} ${acl}
                    """)
                }
            }
        }
        if (it == 'powervs') {
            uploaders["powervs"] = {
                tryWithCredentials([file(variable: "POWERVS_IMAGE_UPLOAD_CONFIG",
                                   credentialsId: "powervs-image-upload-config")]) {
                    def cloud_object_storage = pipecfg.clouds.powervs.cloud_object_storage
                    shwrap("""cosa buildextend-powervs \
                        --upload \
                        --cloud-object-storage ${cloud_object_storage} \
                        --bucket ${bucket} \
                        --region ${primary_region} \
                        --credentials-file \${"POWER_IMAGE_UPLOAD_CONFIG"} \
                        --build ${buildID} \
                        --force
                     """);
                }
            }
        }
        if (it == 'kubevirt') {
            uploaders["kubevirt"] = {
                tryWithCredentials([file(variable: "KUBEVIRT_IMAGE_UPLOAD_CONFIG",
                                   credentialsId: "kubevirt-image-upload-config")]) {
                    def name =  pipecfg.clouds.kubevirt.name
                    def repository =  pipecfg.clouds.kubevirt.repository
                    shwrap("""coreos-assembler buildextend-kubevirt \
                                 --upload \
                                 --name ${name} \
                                 --repository ${repository}
                    """);
                }
            }
        }
    }
    parallel uploaders
}
return this
