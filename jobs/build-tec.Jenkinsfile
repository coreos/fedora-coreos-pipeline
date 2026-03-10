node {
    checkout scm
    pipeutils = load("utils.groovy")
    pipecfg = pipeutils.load_pipecfg()
}

properties([
    parameters([
      choice(name: 'COREOS_TYPE',
             choices: ['fedora-coreos', 'redhat-coreos'],
             description: 'CoreOS type to build'),
      string(name: 'CONTAINERFILE_REPO',
             description: 'Git repository containing your Containerfile',
             defaultValue: 'https://github.com/trusted-execution-clusters/investigations.git',
             trim: true),
      string(name: 'CONTAINERFILE_REF',
             description: 'Git branch/tag/commit to checkout',
             defaultValue: 'main',
             trim: true),
      string(name: 'STREAM',
             description: 'Stream name for S3 upload path',
             defaultValue: 'tec',
             trim: true),
      booleanParam(name: 'NO_UPLOAD',
             defaultValue: false,
             description: 'Skip upload to S3'),
    ]),
    buildDiscarder(logRotator(
        numToKeepStr: '50',
        artifactNumToKeepStr: '30'
    )),
    durabilityHint('PERFORMANCE_OPTIMIZED')
])

// Set base image, osname, and config repo based on CoreOS type
def base_image = ""
def coreos_osname = ""
def coreos_config_repo = ""
def kbc_img_standard = ""
def kbc_img_without_tpm = ""
def clevis_pin_img = ""
def ignition_img = ""
if (params.COREOS_TYPE == "fedora-coreos") {
    base_image = "quay.io/trusted-execution-clusters/fedora-coreos@sha256:6997f51fd27d1be1b5fc2e6cc3ebf16c17eb94d819b5d44ea8d6cf5f826ee773"
    coreos_osname = "fedora-coreos"
    coreos_config_repo = "https://github.com/coreos/fedora-coreos-config.git"
    kbc_img_standard = "quay.io/trusted-execution-clusters/trustee-attester:fedora-standard-ea582b3"
    kbc_img_without_tpm = "quay.io/trusted-execution-clusters/trustee-attester:fedora-without-tpm-v0.17.0"
    clevis_pin_img = "quay.io/trusted-execution-clusters/clevis-pin-trustee:fedora-v0.17.0"
    ignition_img = "quay.io/trusted-execution-clusters/ignition:fedora-5a45ee84"
} else if (params.COREOS_TYPE == "redhat-coreos") {
    base_image = "quay.io/openshift-release-dev/ocp-v4.0-art-dev@sha256:905508f17491d092ce5c81283cac740bb7616d2dcf1947c883849a3a76cf9c4f"
    coreos_osname = "rhcos"
    coreos_config_repo = "https://github.com/coreos/fedora-coreos-config.git"
    kbc_img_standard = "quay.io/trusted-execution-clusters/trustee-attester:centos-stream-standard-v0.17.0"
    kbc_img_without_tpm = "quay.io/trusted-execution-clusters/trustee-attester:centos-stream-without-tpm-v0.17.0"
    clevis_pin_img = "quay.io/trusted-execution-clusters/clevis-pin-trustee:centos-stream-75015a5"
    ignition_img = "quay.io/trusted-execution-clusters/ignition:centos-stream-5a45ee84"
}

def build_description = "[${params.COREOS_TYPE}]"
currentBuild.description = "${build_description} Waiting"

def timeout_mins = 60

lock(resource: "build-tec") {
    cosaPod(cpu: "2",
            memory: "4Gi",
            image: "quay.io/coreos-assembler/coreos-assembler:latest",
            serviceAccount: "jenkins") {
    timeout(time: timeout_mins, unit: 'MINUTES') {
    try {

        currentBuild.description = "${build_description} Running"

        // this is defined IFF we *should* and we *can* upload to S3
        def s3_stream_dir
        if (pipecfg.s3 && pipeutils.AWSBuildUploadCredentialExists()) {
            s3_stream_dir = pipeutils.get_s3_streams_dir(pipecfg, params.STREAM)
        }

        // Determine if we should upload to S3
        def uploading = false
        if (s3_stream_dir && !params.NO_UPLOAD) {
            uploading = true
        }

        def src_commit = shwrapCapture("git ls-remote ${params.CONTAINERFILE_REPO} refs/heads/${params.CONTAINERFILE_REF} | cut -d \$'\t' -f 1")
        def short_commit = src_commit.substring(0,7)
        build_description = "${build_description} ${short_commit}"
        currentBuild.description = "${build_description} Running"
        echo "Source commit: ${src_commit}"

        // Define a closure to build and import a container with specific KBC image
        def buildAndImportContainer = { kbc_img, variant_name ->
            def image_name = "build-image"
            def image_tag = "${variant_name}"
            def tarball = "build-image-${variant_name}.tar"

            stage("Build Container (${variant_name})") {
                withCredentials([file(credentialsId: 'oscontainer-push-registry-secret', variable: 'REGISTRY_AUTH_FILE')]) {
                    pipeutils.withPodmanRemoteArchBuilder(arch: "x86_64") {
                        // Build using podman on remote x86_64 builder
                        // Sync credentials to remote session for pulling base images
                        utils.syncCredentialsIfInRemoteSession(["REGISTRY_AUTH_FILE"])
                        shwrap("""
                        # Clone repository if not already present
                        if [ ! -d src-repo ]; then
                            git clone ${params.CONTAINERFILE_REPO} src-repo
                            cd src-repo
                            git checkout ${params.CONTAINERFILE_REF}
                        else
                            cd src-repo
                            git fetch origin
                            git checkout ${params.CONTAINERFILE_REF}
                        fi
                        cd coreos
                        podman build \
                            --build-arg BASE=${base_image} \
                            --build-arg COM_COREOS_OSNAME=${coreos_osname} \
                            --build-arg KBC_IMG=${kbc_img} \
                            --build-arg CLEVIS_PIN_IMG=${clevis_pin_img} \
                            --build-arg IGNITION_IMG=${ignition_img} \
                            -f Containerfile \
                            -t ${image_name}:${image_tag} \
                            .
                        """)
                    }
                }
                echo "Built container: ${image_name}:${image_tag} with KBC image ${kbc_img}"
            }

            stage("Save Container Image (${variant_name})") {
                pipeutils.withPodmanRemoteArchBuilder(arch: "x86_64") {
                    // Save the remote image to a local OCI archive tarball
                    shwrap("podman save --format oci-archive ${image_name}:${image_tag} -o ${tarball}")
                }
                echo "Saved container image to ${tarball}"
            }

            stage("Import Container Image (${variant_name})") {
                shwrap("""
                cosa import oci-archive:${tarball}
                """)
                echo "Imported container image from ${tarball}"
            }
        }

        stage('Initialize CoreOS Config') {
            shwrap("""
            cosa init --force ${coreos_config_repo}
            """)
            echo "Initialized CoreOS configuration from ${coreos_config_repo}"
        }

        // Phase 1: Build Azure with without-tpm attester
        // Build and import container with without-tpm attester for Azure
        buildAndImportContainer(kbc_img_without_tpm, "without-tpm")

        stage('Build Azure Image') {
            shwrap("""
            cosa osbuild azure
            """)
            echo "Built Azure image"
        }

        stage('Compress Azure Artifact') {
            shwrap("""
            # Find and compress the Azure VHD file with xz
            AZURE_VHD=\$(cosa meta --get-value images.azure.path)
            if [ -n "\${AZURE_VHD}" ]; then
                # The path is relative to the build directory, so we need to prefix it
                BUILD_DIR=\$(readlink -f builds/latest/x86_64)
                AZURE_VHD_FULL="\${BUILD_DIR}/\${AZURE_VHD}"
                if [ -f "\${AZURE_VHD_FULL}" ]; then
                    echo "Compressing Azure artifact: \${AZURE_VHD_FULL}"
                    # Limit memory to avoid OOM kills in the 4GB pod
                    env XZ_DEFAULTS=--memlimit=4G xz -T0 -9 "\${AZURE_VHD_FULL}"
                    echo "Compressed to: \${AZURE_VHD_FULL}.xz"

                    # Update metadata to reflect the compressed file
                    AZURE_VHD_XZ="\${AZURE_VHD}.xz"
                    cosa meta --set images.azure.path="\${AZURE_VHD_XZ}"
                else
                    echo "No Azure VHD file found at \${AZURE_VHD_FULL}"
                fi
            else
                echo "No Azure VHD path in metadata"
            fi
            """)
            echo "Compressed Azure artifact with xz"
        }

        if (uploading) {
            stage('Upload Azure to S3') {
                def acl = pipecfg.s3.acl ?: 'public-read'
                pipeutils.shwrapWithAWSBuildUploadCredentials("""
                cosa buildupload --skip-builds-json s3 \
                    --aws-config-file \${AWS_BUILD_UPLOAD_CONFIG} \
                    --acl=${acl} ${s3_stream_dir}/builds
                """)
                echo "Uploaded Azure artifacts to s3://${s3_stream_dir}/builds"
            }
        }

        // Phase 2: Clean cache and build QEMU/KubeVirt with standard attester
        // COMMENTED OUT FOR TESTING - Uncomment after Azure testing is complete
        /*
        stage('Clean Cache for QEMU/KubeVirt Build') {
            shwrap("""
            # Clean builds and cache to avoid Build ID conflict
            rm -rf builds/* tmp/* cache/*
            # Re-initialize CoreOS config
            cosa init --force ${coreos_config_repo}
            """)
            echo "Cleaned cache and re-initialized for QEMU/KubeVirt build"
        }

        // Build and import container with standard attester for QEMU and KubeVirt
        buildAndImportContainer(kbc_img_standard, "standard")

        stage('Build QEMU Image') {
            shwrap("""
            cosa osbuild qemu
            """)
            echo "Built QEMU image"
        }

        stage('Compress QEMU Artifact') {
            shwrap("""
            # Find and compress the QEMU qcow2 file with xz
            QEMU_QCOW2=\$(cosa meta --get-value images.qemu.path)
            if [ -n "\${QEMU_QCOW2}" ]; then
                # The path is relative to the build directory, so we need to prefix it
                BUILD_DIR=\$(readlink -f builds/latest/x86_64)
                QEMU_QCOW2_FULL="\${BUILD_DIR}/\${QEMU_QCOW2}"
                if [ -f "\${QEMU_QCOW2_FULL}" ]; then
                    echo "Compressing QEMU artifact: \${QEMU_QCOW2_FULL}"
                    # Limit memory to avoid OOM kills in the 4GB pod
                    env XZ_DEFAULTS=--memlimit=4G xz -T0 -9 "\${QEMU_QCOW2_FULL}"
                    echo "Compressed to: \${QEMU_QCOW2_FULL}.xz"

                    # Update metadata to reflect the compressed file
                    QEMU_QCOW2_XZ="\${QEMU_QCOW2}.xz"
                    cosa meta --set images.qemu.path="\${QEMU_QCOW2_XZ}"
                else
                    echo "No QEMU qcow2 file found at \${QEMU_QCOW2_FULL}"
                fi
            else
                echo "No QEMU qcow2 path in metadata"
            fi
            """)
            echo "Compressed QEMU artifact with xz"
        }

        stage('Build KubeVirt Image') {
            shwrap("""
            cosa osbuild kubevirt
            """)
            echo "Built KubeVirt image"
        }

        if (uploading) {
            stage('Upload QEMU/KubeVirt to S3') {
                def acl = pipecfg.s3.acl ?: 'public-read'
                pipeutils.shwrapWithAWSBuildUploadCredentials("""
                cosa buildupload --skip-builds-json s3 \
                    --aws-config-file \${AWS_BUILD_UPLOAD_CONFIG} \
                    --acl=${acl} ${s3_stream_dir}/builds
                """)
                echo "Uploaded QEMU/KubeVirt artifacts to s3://${s3_stream_dir}/builds"
            }
        }
        */

        currentBuild.result = 'SUCCESS'
        currentBuild.description = "${build_description} ✅"

    } catch (e) {
        currentBuild.result = 'FAILURE'
        currentBuild.description = "${build_description} ❌"
        throw e
    } finally {
        def message = "[TEC ${params.COREOS_TYPE}]"

        if (currentBuild.result == 'SUCCESS') {
            message = ":sparkles: ${message} Build completed successfully"
        } else {
            message = ":fire: ${message} Build failed"
        }

        message = "${message} #${env.BUILD_NUMBER} <${env.BUILD_URL}|:jenkins:>"
        echo message
        pipeutils.trySlackSend(message: message)
    }
}}} // timeout, cosaPod, and lock
