
// Upload artifacts to clouds
def upload_to_clouds(pipecfg, basearch, buildID, stream) {

    // Get artifacts to upload, if we have an artifact built
    // try to uplaoad it
    def meta_json = "builds/${buildID}/${basearch}/meta.json"
    def meta = readJSON file: meta_json
    def artifacts = meta['images'].keySet() as List
    def uploaders = [:]

    artifacts.each {

        if (it == 'aliyun') {
            uploaders["aliyun"] = {
                tryWithCredentials([file(variable: "ALIYUN_IMAGE_UPLOAD_CONFIG",
                                   credentialsId: "aliyun-image-upload-config")]) {
                    def c = pipecfg.clouds.aliyun
                    shwrap("""
                    cosa buildextend-aliyun \
                        --upload \
                        --arch=${basearch} \
                        --build=${buildID} \
                        --region=${c.primary_region} \
                        --bucket=s3://${c.bucket} \
                        --credentials-file=\${ALIYUN_IMAGE_UPLOAD_CONFIG}
                    """)
                }
            }
        }
        if (it == 'aws') {
            uploaders["aws"] = {
                tryWithCredentials([file(variable: "AWS_BUILD_UPLOAD_CONFIG",
                                   credentialsId: "aws-build-upload-config")]) {
                    def c = pipecfg.clouds.aws
                    def grant_user_args = c.test_accounts?.collect{"--grant-user ${it}"}.join(" ")
                    shwrap("""
                    cosa buildextend-aws \
                        --upload \
                        --arch=${basearch} \
                        --build=${buildID} \
                        --region=${c.primary_region} ${grant_user_args} \
                        --bucket=s3://${c.bucket} \
                        --credentials-file=\${AWS_BUILD_UPLOAD_CONFIG}
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
                    def c = pipecfg.clouds.azure
                    shwrap("""cosa buildextend-azure \
                        --upload \
                        --auth \${AZURE_IMAGE_UPLOAD_CONFIG}/azure.json \
                        --build=${buildID} \
                        --container=${c.container} \
                        --profile \${AZURE_IMAGE_UPLOAD_CONFIG}/azureProfile.json \
                        --resource-group ${c.resource_group} \
                        --storage-account ${c.storage_account} \
                        --force
                     """)
                }
            }
        }
        if (it == 'gcp') {
            uploaders["gcp"] = {
                tryWithCredentials([file(variable: "GCP_IMAGE_UPLOAD_CONFIG",
                                   credentialsId: "gcp-image-upload-config")]) {
                    def c = pipecfg.clouds.gcp
                    def create_image =  (c.create_image ? "--create-image=true": "")
                    def description =  (c.description ? ('--description=' + c.description): "")
                    def family =  (c.family.image_family ? '--family ' + c.family.image_family + '--deprecated': "")
                    def acl = (c.public ? "--public" : "")
                    def gcp_licenses = c.licenses
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
                        --bucket gs://${c.bucket} \
                        --json \${GCP_IMAGE_UPLOAD_CONFIG} \
                        ${create_image} ${description} ${family} ${licenses} ${acl}
                    """)
                }
            }
        }
        if (it == 'kubevirt') {
            uploaders["kubevirt"] = {
                tryWithCredentials([file(variable: "KUBEVIRT_IMAGE_UPLOAD_CONFIG",
                                   credentialsId: "kubevirt-image-upload-config")]) {
                    def c = pipecfg.clouds.kubevirt
                    shwrap("""coreos-assembler buildextend-kubevirt \
                                 --upload \
                                 --name ${c.name} \
                                 --repository ${c.repository}
                    """);
                }
            }
        }
        if (it == 'powervs') {
            uploaders["powervs"] = {
                tryWithCredentials([file(variable: "POWERVS_IMAGE_UPLOAD_CONFIG",
                                   credentialsId: "powervs-image-upload-config")]) {
                    def c = pipecfg.clouds.powervs
                    // for powervs in RHCOS the images are uploaded to a bucket in each
                    // region that is uniquely named with the region as a suffix
                    // i.e. `rhcos-powervs-images-us-east`
                    def bucket = "${c.bucket}-${c.primary_region}"
                    shwrap("""cosa buildextend-powervs \
                        --upload \
                        --cloud-object-storage ${c.cloud_object_storage_service_instance} \
                        --bucket ${bucket} \
                        --region ${c.primary_region} \
                        --credentials-file \${"POWER_IMAGE_UPLOAD_CONFIG"} \
                        --build ${buildID} \
                        --force
                     """);
                }
            }
        }
    }
    parallel uploaders
}
return this
