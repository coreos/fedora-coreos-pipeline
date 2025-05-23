
// Replicate artifacts in various clouds
def replicate_to_clouds(pipecfg, basearch, buildID, stream) {

    def meta = readJSON(text: shwrapCapture("""
        cosa meta --build=${buildID} --arch=${basearch} --dump
    """))
    def replicators = [:]
    def builders = [:]
    def credentials
    def stream_info = pipecfg.streams[stream]

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

    // For AWS we need to consider the primary AWS partition and the
    // GovCloud partition. Define a closure here that we'll call for both.
    def awsReplicateClosure = { config, credentialId ->
        def creds = [file(variable: "AWS_CONFIG_FILE", credentialsId: credentialId)]
        withCredentials(creds) {
            def c = config
            shwrap("""
            cosa aws-replicate \
                --log-level=INFO \
                --build=${buildID} \
                --arch=${basearch} \
                --source-region=${c.primary_region} \
                --credentials-file=\${AWS_CONFIG_FILE}
            """)
        }
    }
    // A closure to build the aws-winli AMI. This will be called before replicating to 
    // all regions, if the option is set for the current stream. `cosa aws-replicate` 
    // will handle both traditional AMIs and aws-winli AMIs if present in the metadata. 
    // aws-winli is only supported on x86_64.
    def awsWinLIBuildClosure = { config, aws_image_name, credentialId ->
        def creds = [file(variable: "AWS_CONFIG_FILE", credentialsId: credentialId)]
        withCredentials(creds) {
            utils.syncCredentialsIfInRemoteSession(["AWS_CONFIG_FILE"])
            def c = config

            // Since we are not uploading anything, let's just touch the vmdk image
            // file to satisfy the cosa ore wrapper, which still requires the file 
            // in the cosa working dir.
            def aws_image_path = "builds/${buildID}/${basearch}/${aws_image_name}"
            shwrap("""
                touch ${aws_image_path}
            """)

            // Discover the latest Windows Server AMI to use as the winli-builder instance.
            // The AMI rotates frequently with Windows updates and is not persistant in AWS
            // for very long, so we need to find the most recent AMI ID.
            // Windows Server 2022 was selected here because the Windows Server 2025 AMI does
            // not allow legacy bios. WS2022 has an EOL date of 2026-10-13.
            // https://learn.microsoft.com/en-us/lifecycle/products/windows-server-2022
            // If WS2022 becomes inaccessible in the future and we still need BIOS for our
            // winli images then we can just use one of our own previously created winli
            // images and pass that to `--windows-ami` below.
            def windows_server_ami_name = ""
            windows_server_ami_name = "Windows_Server-2022-English-Full-Base-*"
            def windows_server_ami = shwrapCapture("""
                aws ec2 describe-images   \
                    --region=${c.primary_region}   \
                    --owners="amazon"   \
                    --filters="Name=name,Values=${windows_server_ami_name}"   \
                    --query="sort_by(Images, &CreationDate)[-1].ImageId"   \
                    --output text
            """)

            // validate that we did actually get an AMI ID returned.
            if (!(windows_server_ami_name != /^ami-[0-9][a-f]{17}$/)) {
                error("Invalid Windows Server AMI ID: ${windows_server_ami}")
            }

            def extraArgs = []
            if (c.grant_users) {
                extraArgs += c.grant_users.collect{"--grant-user=${it}"}
                extraArgs += c.grant_users.collect{"--grant-user-snapshot=${it}"}
            }
            if (c.tags) {
                extraArgs += c.tags.collect { "--tags=${it}" }
            }
            if (c.public) {
                extraArgs += "--public"
            }
            shwrap("""
                cosa imageupload-aws \
                    --upload \
                    --winli \
                    --windows-ami=${windows_server_ami} \
                    --arch=${basearch} \
                    --build=${buildID} \
                    --region=${c.primary_region} \
                    --credentials-file=\${AWS_CONFIG_FILE} \
                    ${extraArgs.join(' ')}
            """)

            // remove the false vmdk file from the builds directory so it doesn't get
            // uploaded by some other process
            shwrap("""
                rm ${aws_image_path}
            """)
        }
    }
    if (meta.amis) {
        credentials = [file(variable: "UNUSED", credentialsId: "aws-build-upload-config")]
        if (pipecfg.clouds?.aws &&
            utils.credentialsExist(credentials)) {

            // grab the aws vmdk image name from the metadata to pass to the winli closure.
            // the cosa ore wrapper still requires the image to exist, but we dont upload 
            // anything so we'll just touch the file in the cosa working dir.
            def aws_image_name = meta.images.aws.path
            // aws-winli is only supported on x86_64
            if ((basearch == "x86_64") && (stream_info.create_and_replicate_winli_ami)) {
                builders["☁️ 🔨:aws-winli"] = {
                    awsWinLIBuildClosure.call(pipecfg.clouds.aws, aws_image_name,
                                        "aws-build-upload-config")
                }
            }
            replicators["☁️ 🔄:aws"] = {
                awsReplicateClosure.call(pipecfg.clouds.aws,
                                         "aws-build-upload-config")
            }
        }
        credentials = [file(variable: "UNUSED", credentialsId: "aws-govcloud-image-upload-config")]
        if (pipecfg.clouds?.aws?.govcloud &&
            utils.credentialsExist(credentials)) {
            replicators["☁️ 🔄:aws:govcloud"] = {
                awsReplicateClosure.call(pipecfg.clouds.aws.govcloud,
                                         "aws-govcloud-image-upload-config")
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

    parallel builders
    parallel replicators
}
// Upload artifacts to clouds
def upload_to_clouds(pipecfg, basearch, buildID, stream) {

    // In 4.19+ we switched the command to upload to clouds to
    // `cosa imageupload-<cloud>`
    // https://github.com/coreos/coreos-assembler/pull/4074
    def image_upload_cmd = "imageupload"
    if (shwrapRc("cosa shell -- test -e /usr/lib/coreos-assembler/cmd-imageupload-aws") != 0) {
        image_upload_cmd = "buildextend"
    }

    // Get a list of the artifacts that are currently built.
    def images_json = readJSON(text: shwrapCapture("""
        cosa meta --build=${buildID} --arch=${basearch} --get-value images
    """))
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
                cosa ${image_upload_cmd}-aliyun \
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

    // For AWS we need to consider the primary AWS partition and the
    // GovCloud partition. Define a closure here that we'll call for both.
    def awsUploadClosure = { config, credentialId ->
        def creds = [file(variable: "AWS_CONFIG_FILE", credentialsId: credentialId)]
        withCredentials(creds) {
            utils.syncCredentialsIfInRemoteSession(["AWS_CONFIG_FILE"])
            def c = config
            def extraArgs = []
            if (c.grant_users) {
                extraArgs += c.grant_users.collect{"--grant-user=${it}"}
                extraArgs += c.grant_users.collect{"--grant-user-snapshot=${it}"}
            }
            if (c.tags) {
                extraArgs += c.tags.collect { "--tags=${it}" }
            }
            if (c.public) {
                extraArgs += "--public"
            }
            shwrap("""
            cosa ${image_upload_cmd}-aws \
                --upload \
                --arch=${basearch} \
                --build=${buildID} \
                --region=${c.primary_region} \
                --bucket=s3://${c.bucket} \
                --credentials-file=\${AWS_CONFIG_FILE} \
                ${extraArgs.join(' ')}
            """)
        }
    }
    if (artifacts.contains("aws")) {
        credentials = [file(variable: "UNUSED", credentialsId: "aws-build-upload-config")]
        if (pipecfg.clouds?.aws &&
            utils.credentialsExist(credentials)) {
            uploaders["☁️ ⬆️ :aws"] = {
                awsUploadClosure.call(pipecfg.clouds.aws,
                                      "aws-build-upload-config")
            }
        }
        credentials = [file(variable: "UNUSED", credentialsId: "aws-govcloud-image-upload-config")]
        if (pipecfg.clouds?.aws?.govcloud &&
            utils.credentialsExist(credentials)) {
            uploaders["☁️ ⬆️ :aws:govcloud"] = {
                awsUploadClosure.call(pipecfg.clouds.aws.govcloud,
                                      "aws-govcloud-image-upload-config")
            }
        }
    }

    credentials = [file(variable: 'AZURE_IMAGE_UPLOAD_CONFIG',
                        credentialsId: 'azure-image-upload-config')]
    if (pipecfg.clouds?.azure &&
        artifacts.contains("azure") &&
        utils.credentialsExist(credentials)) {
        def creds = credentials
        uploaders["☁️ ⬆️ :azure"] = {
            withCredentials(creds) {
                utils.syncCredentialsIfInRemoteSession(["AZURE_IMAGE_UPLOAD_CONFIG"])
                def c = pipecfg.clouds.azure
                shwrap("""cosa ${image_upload_cmd}-azure \
                    --upload \
                    --credentials \${AZURE_IMAGE_UPLOAD_CONFIG} \
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
                if (c.family?."${basearch}") {
                    // If there is an image family then we set it on image creation
                    // and also start the image in a deprecated state, which will be
                    // un-deprecated in the release job.
                    extraArgs += "--family=" + utils.substituteStr(c.family."${basearch}", [STREAM: stream])
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
                cosa ${image_upload_cmd}-gcp \
                    --log-level=INFO \
                    --build=${buildID} \
                    --arch=${basearch} \
                    --upload \
                    --project=\${gcp_project} \
                    --bucket gs://${c.bucket} \
                    --json \${GCP_IMAGE_UPLOAD_CONFIG} \
                    ${extraArgs.join(' ')}
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
                shwrap("""cosa ${image_upload_cmd}-powervs \
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
    // It shouldn't take more than 45 minutes.
    timeout(time: 45, unit: 'MINUTES') {
        parallel uploaders
    }
}
return this
