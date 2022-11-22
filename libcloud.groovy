
// Replicate artifacts in various clouds
def replicate_to_clouds(pipecfg, basearch, buildID) {

    def meta = readJSON(text: shwrapCapture("cosa meta --arch=${basearch} --dump"))
    def replicators = [:]
    def credentials

    credentials = [file(variable: "ALIYUN_IMAGE_UPLOAD_CONFIG",
                        credentialsId: "aliyun-image-upload-config")]
    if (meta.aliyun) {
        def creds = credentials
        replicators["☁️ 🔄:aliyun"] = {
            withCredentials(creds) {
                def c = pipecfg.clouds.aliyun
                def extraArgs = []
                if (c.public) {
                    extraArgs += "--public"
                }
                shwrap("""
                coreos-assembler aliyun-replicate \
                    --build=${buildID} \
                    --arch=${basearch} \
                    --config=\${ALIYUN_IMAGE_UPLOAD_CONFIG} \
                    ${extraArgs.join(' ')}
                """)
            }
        }
    }
    if (meta.amis) {
        replicators["☁️ 🔄:aws"] = {
            def replicated = false
            def ids = ['aws-build-upload-config', 'aws-govcloud-image-upload-config']
            def c = pipecfg.clouds.aws
            for (id in ids) {
                tryWithCredentials([file(variable: 'AWS_CONFIG_FILE', credentialsId: id)]) {
                    shwrap("""
                    cosa aws-replicate \
                        --build=${buildID} \
                        --arch=${basearch} \
                        --credentials-file=\${AWS_CONFIG_FILE} \
                        --log-level=INFO
                    """)
                    replicated = true
                }
            }
            if (!replicated) {
                        error("AWS Replication asked for but no credentials exist")
            }
        }
    }
    credentials = [file(variable: "POWERVS_IMAGE_UPLOAD_CONFIG",
                        credentialsId: "powervs-image-upload-config")]
    if (meta.powervs) {
        def creds = credentials
        replicators["☁️ 🔄:powervs"] = {
            withCredentials(creds) {
                def c = pipecfg.clouds.powervs
                // for powervs in RHCOS the images are uploaded to a bucket in each
                // region that is uniquely named with the region as a suffix
                // i.e. `rhcos-powervs-images-us-east`
                def regions = []
                if (c.regions) {
                     regions = c.regions.join(" ")
                }
                shwrap("""
                cosa powervs-replicate \
                    --cloud-object-storage ${c.cloud_object_storage_service_instance} \
                    --build ${buildID} \
                    --arch=${basearch} \
                    --bucket-prefix ${c.bucket} \
                    --regions ${regions} \
                    --credentials-file \${POWERVS_IMAGE_UPLOAD_CONFIG}
                """)
            }
        }
    }

    parallel replicators
}
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
    if (pipecfg.clouds?.aliyun &&
        artifacts.contains("aliyun") &&
        utils.credentialsExist(credentials)) {
        def creds = credentials
        uploaders["☁️ ⬆️ :aliyun"] = {
            withCredentials(creds) {
                utils.syncCredentialsIfInRemoteSession(["ALIYUN_IMAGE_UPLOAD_CONFIG"])
                def c = pipecfg.clouds.aliyun
                def extraArgs = []
                if (c.public) {
                    extraArgs += "--public"
                }
                shwrap("""
                cosa buildextend-aliyun \
                    --upload \
                    --arch=${basearch} \
                    --build=${buildID} \
                    --region=${c.primary_region} \
                    --bucket=${c.bucket} \
                    --config=\${ALIYUN_IMAGE_UPLOAD_CONFIG} \
                    ${extraArgs.join(' ')}
                """)
            }
        }
    }
    credentials = [file(variable: "AWS_BUILD_UPLOAD_CONFIG",
                        credentialsId: "aws-build-upload-config")]
    if (pipecfg.clouds?.aws &&
        artifacts.contains("aws") &&
        utils.credentialsExist(credentials)) {
        def creds = credentials
        uploaders["☁️ ⬆️ :aws"] = {
            withCredentials(creds) {
                utils.syncCredentialsIfInRemoteSession(["AWS_BUILD_UPLOAD_CONFIG"])
                def c = pipecfg.clouds.aws
                def extraArgs = []
                if (c.test_accounts) {
                    extraArgs += c.test_accounts.collect{"--grant-user=${it}"}
                }
                if (c.public) {
                    extraArgs += "--public"
                }
                shwrap("""
                cosa buildextend-aws \
                    --upload \
                    --arch=${basearch} \
                    --build=${buildID} \
                    --region=${c.primary_region} \
                    --bucket=s3://${c.bucket} \
                    --credentials-file=\${AWS_BUILD_UPLOAD_CONFIG} \
                    ${extraArgs.join(' ')} 
                """)
            }
        }
    }
    credentials = [file(variable: "AWS_GOVCLOUD_IMAGE_UPLOAD_CONFIG",
                        credentialsId: "aws-govcloud-image-upload-config")]
    if (pipecfg.clouds?.aws?.govcloud &&
        artifacts.contains("aws") &&
        utils.credentialsExist(credentials)) {
        def creds = credentials
        uploaders["☁️ ⬆️ :aws:govcloud"] = {
            withCredentials(creds) {
                utils.syncCredentialsIfInRemoteSession(["AWS_GOVCLOUD_IMAGE_UPLOAD_CONFIG"])
                def c = pipecfg.clouds.aws.govcloud
                def extraArgs = []
                if (c.test_accounts) {
                    extraArgs += c.test_accounts.collect{"--grant-user=${it}"}
                }
                if (c.public) {
                    extraArgs += "--public"
                }
                shwrap("""
                cosa buildextend-aws \
                    --upload \
                    --arch=${basearch} \
                    --build=${buildID} \
                    --region=${c.primary_region} \
                    --bucket=s3://${c.bucket} \
                    --credentials-file=\${AWS_GOVCLOUD_IMAGE_UPLOAD_CONFIG} \
                    ${extraArgs.join(' ')} 
                """)
            }
        }
    }
    credentials = [file(variable: 'AZURE_IMAGE_UPLOAD_CONFIG_AUTH',
                        credentialsId: 'azure-image-upload-config-auth'),
                   file(variable: 'AZURE_IMAGE_UPLOAD_CONFIG_PROFILE',
                        credentialsId: 'azure-image-upload-config-profile')]
    if (pipecfg.clouds?.azure &&
        artifacts.contains("azure") &&
        utils.credentialsExist(credentials)) {
        def creds = credentials
        uploaders["☁️ ⬆️ :azure"] = {
            withCredentials(creds) {
                utils.syncCredentialsIfInRemoteSession(["AZURE_IMAGE_UPLOAD_CONFIG_AUTH",
                                                        "AZURE_IMAGE_UPLOAD_CONFIG_PROFILE"])
                def c = pipecfg.clouds.azure
                shwrap("""cosa buildextend-azure \
                    --upload \
                    --auth \${AZURE_IMAGE_UPLOAD_CONFIG_AUTH} \
                    --profile \${AZURE_IMAGE_UPLOAD_CONFIG_PROFILE} \
                    --build=${buildID} \
                    --resource-group ${c.resource_group} \
                    --storage-account ${c.storage_account} \
                    --container=${c.storage_container}
                 """)
            }
        }
    }
    credentials = [file(variable: "GCP_IMAGE_UPLOAD_CONFIG",
                        credentialsId: "gcp-image-upload-config")]
    if (pipecfg.clouds?.gcp &&
        artifacts.contains("gcp") &&
        utils.credentialsExist(credentials)) {
        def creds = credentials
        uploaders["☁️ ⬆️ :gcp"] = {
            withCredentials(creds) {
                utils.syncCredentialsIfInRemoteSession(["GCP_IMAGE_UPLOAD_CONFIG"])
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
    if (pipecfg.clouds?.kubevirt &&
        artifacts.contains("kubevirt") &&
        utils.credentialsExist(credentials)) {
        def creds = credentials
        uploaders["☁️ ⬆️ :kubevirt"] = {
            withCredentials(creds) {
                utils.syncCredentialsIfInRemoteSession(["KUBEVIRT_IMAGE_UPLOAD_CONFIG"])
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
    if (pipecfg.clouds?.powervs &&
        artifacts.contains("powervs") &&
        utils.credentialsExist(credentials)) {
        def creds = credentials
        uploaders["☁️ ⬆️ :powervs"] = {
            withCredentials(creds) {
                utils.syncCredentialsIfInRemoteSession(["POWERVS_IMAGE_UPLOAD_CONFIG"])
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
                    --credentials-file \${POWERVS_IMAGE_UPLOAD_CONFIG} \
                    --build ${buildID}
                 """);
            }
        }
    }

    // Run the resulting set of uploaders in parallel
    parallel uploaders
}
return this
