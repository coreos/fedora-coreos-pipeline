
// Upload artifacts to clouds
def upload_to_clouds(pipecfg, basearch, buildID, stream) {

    // Get a list of the artifacts that are currently built.
    def images_json = readJSON(text: shwrapCapture("cosa meta --get-value images"))
    def artifacts = images_json.keySet()

    // Define an uploader closure for each artifact/cloud that we
    // support uploading to. Only add the closure to the map if the
    // artifact exists and we have credentials in our Jenkins env for
    // uploading to that cloud.
    def uploaders = [:]
    def credentials

    credentials = [file(variable: "ALIYUN_IMAGE_UPLOAD_CONFIG",
                        credentialsId: "aliyun-image-upload-config")]
    if (artifacts.contains("aliyun") && utils.credentialsExist(credentials)) {
        def creds = credentials
        uploaders["aliyun"] = {
            withCredentials(creds) {
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
    credentials = [file(variable: "AWS_BUILD_UPLOAD_CONFIG",
                        credentialsId: "aws-build-upload-config")]
    if (artifacts.contains("aws") && utils.credentialsExist(credentials)) {
        def creds = credentials
        uploaders["aws"] = {
            withCredentials(creds) {
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
    credentials = [file(variable: 'AZURE_IMAGE_UPLOAD_CONFIG_AUTH',
                        credentialsId: 'azure-image-upload-config-auth'),
                   file(variable: 'AZURE_IMAGE_UPLOAD_CONFIG_PROFILE',
                        credentialsId: 'azure-image-upload-config-profile')]
    if (artifacts.contains("azure") && utils.credentialsExist(credentials)) {
        def creds = credentials
        uploaders["azure"] = {
            withCredentials(creds) {
                def c = pipecfg.clouds.azure
                shwrap("""cosa buildextend-azure \
                    --upload \
                    --auth \${AZURE_IMAGE_UPLOAD_CONFIG_AUTH} \
                    --profile \${AZURE_IMAGE_UPLOAD_CONFIG_PROFILE} \
                    --build=${buildID} \
                    --resource-group ${c.resource_group} \
                    --storage-account ${c.storage_account} \
                    --container=${c.storage_container} \
                    --force
                 """)
            }
        }
    }
    credentials = [file(variable: "GCP_IMAGE_UPLOAD_CONFIG",
                        credentialsId: "gcp-image-upload-config")]
    if (artifacts.contains("gcp") && utils.credentialsExist(credentials)) {
        def creds = credentials
        uploaders["gcp"] = {
            withCredentials(creds) {
                def c = pipecfg.clouds.gcp
                def extraArgs = []
                if (c.family) {
                    // If there is an image family then we set it on image creation
                    // and also start the image in a deprecated state, which will be
                    // un-deprecated in the release job.
                    extraArgs += "--family=" + utils.substituteStr(c.family, [STREAM: stream])
                    extraArgs += "--deprecated"
                }
                // Apply image description if provided
                if (c.description) {
                    def description = utils.substituteStr(c.description, [
                                                          BUILDID: buildID,
                                                          STREAM: stream,
                                                          BASEARCH: basearch,
                                                          DATE: shwrapCapture("date +%Y-%m-%d")])
                    extraArgs += "--description=\"${description}\""
                }
                // Apply specified licenses to the created image
                if (c.licenses) {
                    for (license in c.licenses) {
                        extraArgs += "--license=" + utils.substituteStr(license, [STREAM: stream])
                    }
                }
                // Mark the image as public if requested
                if (c.public) {
                    extraArgs += "--public"
                }
                shwrap("""
                # pick up the project to use from the config
                gcp_project=\$(jq -r .project_id \${GCP_IMAGE_UPLOAD_CONFIG})
                cosa buildextend-gcp \
                    --log-level=INFO \
                    --build=${buildID} \
                    --upload \
                    --project=\${gcp_project} \
                    --bucket gs://${c.bucket} \
                    --json \${GCP_IMAGE_UPLOAD_CONFIG} \
                    ${extraArgs.join(' ')}
                """)
            }
        }
    }
    credentials = [file(variable: "KUBEVIRT_IMAGE_UPLOAD_CONFIG",
                        credentialsId: "kubevirt-image-upload-config")]
    if (artifacts.contains("kubevirt") && utils.credentialsExist(credentials)) {
        def creds = credentials
        uploaders["kubevirt"] = {
            withCredentials(creds) {
                def c = pipecfg.clouds.kubevirt
                shwrap("""coreos-assembler buildextend-kubevirt \
                             --upload \
                             --name ${c.name} \
                             --repository ${c.repository}
                """)
            }
        }
    }
    credentials = [file(variable: "POWERVS_IMAGE_UPLOAD_CONFIG",
                        credentialsId: "powervs-image-upload-config")]
    if (artifacts.contains("powervs") && utils.credentialsExist(credentials)) {
        def creds = credentials
        uploaders["powervs"] = {
            withCredentials(creds) {
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

    // Run the resulting set of uploaders in parallel
    parallel uploaders
}
return this
