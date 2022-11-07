import org.jenkinsci.plugins.credentialsbinding.impl.CredentialNotFoundException
import org.yaml.snakeyaml.Yaml

// Only add pipeline-specific things here. Otherwise add to coreos-ci-lib
// instead.

// map of Jenkins URL to (S3 bucket, S3 bucket builds key, hotfix allowed)
PROTECTED_JENKINSES = [
    'https://jenkins-fedora-coreos-pipeline.apps.ocp.fedoraproject.org/':
        ['fcos-builds', 'prod/streams/${STREAM}', false],
    'https://jenkins-rhcos.apps.ocp-virt.prod.psi.redhat.com/':
        ['rhcos-ci', 'prod/streams/${STREAM}', true],
    'https://jenkins-rhcos-art.apps.ocp-virt.prod.psi.redhat.com/':
        ['art-rhcos-ci', 'prod/streams/${STREAM}', true]
]

def load_jenkins_config() {
    return readJSON(text: shwrapCapture("""
        oc get configmap -n ${env.PROJECT_NAME} -o json jenkins-config | jq .data
    """))
}

def load_pipecfg() {
    def jenkinscfg = load_jenkins_config()
    def url = jenkinscfg['pipecfg-url']
    def ref = jenkinscfg['pipecfg-ref']

    def pipecfg
    if (url == 'in-tree') {
        pipecfg = readYaml(file: "config.yaml")
    } else {
        // this uses the `checkout` workflow step instead of just manually cloning so
        // that changes show up in the Jenkins UI
        checkout([
            $class: 'GitSCM',
            branches: [[name: "origin/${ref}"]],
            extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'pipecfg']],
            userRemoteConfigs: [[url: url]]
        ])
        pipecfg = readYaml(file: "pipecfg/config.yaml")
    }
    validate_pipecfg(pipecfg)
    return pipecfg
}

def validate_pipecfg(pipecfg) {
    if (env.JENKINS_URL in PROTECTED_JENKINSES) {
        // We're running on a protected Jenkins instance. Check the hotfix
        // build policy and that S3 buckets are as expected.
        def (bucket, key, allow_hotfixes) = PROTECTED_JENKINSES[env.JENKINS_URL]
        if (pipecfg.hotfix && !allow_hotfixes) {
            error("Hotfix builds are not allowed on ${env.JENKINS_URL}")
        }
        if (pipecfg.s3 && (bucket != pipecfg.s3.bucket || key != pipecfg.s3.builds_key)) {
            def target_bucket_key = "${pipecfg.s3.bucket}/${pipecfg.s3.builds_key}"
            error("S3 bucket/key on ${env.JENKINS_URL} must be ${bucket}/${key} (found ${target_bucket_key})")
        }
    } else if (AWSBuildUploadCredentialExists()) {
        // We're *not* running on a protected Jenkins instance and we have AWS
        // creds available. Check that we're not trying to push to a protected
        // S3 bucket.

        // Transform PROTECTED_JENKINSES to a map of {bucket -> jenkins URL}
        // for easier lookup and reporting
        def protected_buckets = PROTECTED_JENKINSES.collectEntries{jenkins_url, v ->
            def (bucket, key, allow_hotfixes) = v
            [bucket, jenkins_url]
        }
        if (pipecfg.s3.bucket in protected_buckets) {
            error("S3 bucket ${pipecfg.s3.bucket} can only be used on ${protected_buckets[pipecfg.s3.bucket]}")
        }
    }
}

def get_source_config_ref_for_stream(pipecfg, stream) {
    if (pipecfg.streams[stream].source_config_ref) {
        return pipecfg.streams[stream].source_config_ref
    } else if (pipecfg.source_config.ref) {
        return utils.substituteStr(pipecfg.source_config.ref, [STREAM: stream])
    } else {
        return stream
    }
}


// Parse and handle the result of Kola
boolean checkKolaSuccess(file) {
    // archive the image if the tests failed
    def report = readJSON(file: "${file}")
    def result = report["result"]
    print("kola result: ${result}")
    if (result != "PASS") {
        return false
    }
    return true
}

def get_s3_streams_dir(pipecfg, stream) {
    def s = pipecfg.s3.bucket
    if (pipecfg.s3.builds_key) {
        def key = utils.substituteStr(pipecfg.s3.builds_key, [STREAM: stream])
        s += "/${key}"
    }
    return s
}

def aws_s3_cp_allow_noent(src, dest) {
    // see similar code in `cosa buildfetch`
    shwrapWithAWSBuildUploadCredentials("""
    export AWS_CONFIG_FILE=\${AWS_BUILD_UPLOAD_CONFIG}
    python3 -c '
import os, sys, tempfile, botocore, boto3
src = sys.argv[1]
dest = sys.argv[2]
assert src.startswith("s3://")
bucket, key = src[len("s3://"):].split("/", 1)
s3 = boto3.client("s3")
try:
    with tempfile.NamedTemporaryFile(mode="wb", dir=os.path.dirname(dest), delete=False) as f:
        s3.download_fileobj(bucket, key, f)
        f.flush()
        os.rename(f.name, dest)
    print(f"Downloaded {src} to {dest}")
except botocore.exceptions.ClientError as e:
    if e.response["Error"]["Code"] != "404":
        raise e
    print(f"{src} does not exist")
    ' '${src}' '${dest}'""")
}

def bump_builds_json(stream, buildid, arch, s3_stream_dir) {
    // Bump the remote builds json with the specified build and
    // update the local builds.json. The workflow is:
    //
    // lock
    //      1. download remote builds.json
    //      2. insert build for specified architecture
    //      3. update the local copy
    //      4. upload updated builds.json to the remote
    // unlock
    //
    // XXX: should fold this into `cosa buildupload` somehow
    lock(resource: "bump-builds-json-${stream}") {
        def remotejson = "s3://${s3_stream_dir}/builds/builds.json"
        aws_s3_cp_allow_noent(remotejson, './remote-builds.json')
        shwrap("""
        # If no remote json exists then this is the first run
        # and we'll just upload the local builds.json
        if [ -f ./remote-builds.json ]; then
            TMPD=\$(mktemp -d) && mkdir \$TMPD/builds
            mv ./remote-builds.json \$TMPD/builds/builds.json
            source /usr/lib/coreos-assembler/cmdlib.sh
            insert_build ${buildid} \$TMPD ${arch}
            mkdir -p builds
            cp \$TMPD/builds/builds.json builds/builds.json
        elif [ -n "\${COREOS_ASSEMBLER_REMOTE_SESSION:-}" ]; then
            # If in a remote session then copy the builds.json back local
            mkdir -p ./builds # make local builds directory first
            cosa shell -- cat builds/builds.json > builds/builds.json
        fi
        """)
        shwrapWithAWSBuildUploadCredentials("""
        export AWS_CONFIG_FILE=\${AWS_BUILD_UPLOAD_CONFIG}
        aws s3 cp --cache-control=max-age=300    \
            --acl=public-read builds/builds.json s3://${s3_stream_dir}/builds/builds.json
        """)
    }
}

// Run in a podman remote context on a builder of the given
// architecture.
//
// Available parameters:
//    arch:  string -- The architecture of the desired host
def withPodmanRemoteArchBuilder(params = [:], Closure body) {
    def arch = params['arch']
    if (arch == "x86_64") {
        // k8s secrets don't support undercores in the name so
        // translate it to 'x86-64'
        arch = "x86-64"
    }
    withPodmanRemote(remoteHost: "coreos-${arch}-builder-host",
                     remoteUid:  "coreos-${arch}-builder-uid",
                     sshKey:     "coreos-${arch}-builder-sshkey") {
        body()
    }
}

// Run in a cosa remote session context on a builder of the given
// architecture.
//
// Available parameters:
// session:  string -- The session ID of the already created session
//    arch:  string -- The architecture of the desired host
def withExistingCosaRemoteSession(params = [:], Closure body) {
    def arch = params['arch']
    def session = params['session']
    withPodmanRemoteArchBuilder(arch: arch) {
        withEnv(["COREOS_ASSEMBLER_REMOTE_SESSION=${session}"]) {
            body()
        }
    }
}

// Returns true if the build was triggered by a push notification.
def triggered_by_push() {
    return (currentBuild.getBuildCauses('com.cloudbees.jenkins.GitHubPushCause').size() > 0)
}

// Runs closure if credentials exist or not.
def tryWithOrWithoutCredentials(creds, Closure body) {
    try {
        withCredentials(creds) {
            body()
        }
    } catch (CredentialNotFoundException e) {
        body()
    }
}

// Runs closure if the fedmsg credentials exist, otherwise gracefully return.
def tryWithMessagingCredentials(Closure body) {
    // Here we need to use `dockerCert`, which was renamed to
    // `x509ClientCert` but the binding credentials plugin hasn't
    // been updated to support the new name. https://stackoverflow.com/a/72293992
    tryWithCredentials([file(variable: 'FEDORA_MESSAGING_CONF',
                             credentialsId: 'fedora-messaging-config'),
                        dockerCert(variable: 'FEDORA_MESSAGING_X509_CERT_PATH',
                                   credentialsId: 'fedora-messaging-coreos-x509-cert')]) {
        // Substitute in the full path to the cert/key into the config
        shwrap('''
        sed -i s,FEDORA_MESSAGING_X509_CERT_PATH,${FEDORA_MESSAGING_X509_CERT_PATH}, ${FEDORA_MESSAGING_CONF}
        ''')
        // Also sync it over to the remote if we're operating in a remote session
        utils.syncCredentialsIfInRemoteSession(["FEDORA_MESSAGING_CONF",
                                                "FEDORA_MESSAGING_X509_CERT_PATH"])
        body()
    }
}

// Injects the root CA cert into the cosa pod if available.
def addOptionalRootCA() {
    tryWithCredentials([file(credentialsId: 'additional-root-ca-cert', variable: 'ROOT_CA')]) {
        shwrap('''
            cp $ROOT_CA /etc/pki/ca-trust/source/anchors/
            /usr/lib/coreos-assembler/update-ca-trust-unpriv
        ''')
        // Also sync it over to the remote if we're operating in a remote session
        shwrap('''
        if [ -n "${COREOS_ASSEMBLER_REMOTE_SESSION:-}" ]; then
            # Can't use `cosa remote-session sync` here because we are
            # running on the remote as the unprivileged builder user
            # and not in the root group. But.. we can use `sudo`.
            cat $ROOT_CA | cosa shell -- sudo tee \
                /etc/pki/ca-trust/source/anchors/$(basename $ROOT_CA)
            cosa shell -- sudo /usr/lib/coreos-assembler/update-ca-trust-unpriv
        fi
        ''')
    }
}

// Maps a list of streams to a list of GitSCM branches.
def streams_as_branches(streams) {
    return streams.collect{ [name: "origin/${it}"] }
}

// Retrieves the stream name from a branch name.
def stream_from_branch(branch) {
    assert branch.startsWith('origin/')
    return branch['origin/'.length()..-1]
}

def streams_of_type(config, type) {
    return config.streams.findAll{k, v -> v.type == type}.collect{k, v -> k}
}

def get_streams_choices(config) {
    def default_stream = config.streams.find{k, v -> v['default'] == true}?.key
    def other_streams = config.streams.keySet().minus(default_stream) as List
    return [default_stream] + other_streams
}

// Returns the default trigger for push notifications. This will trigger builds
// when SCMs change (either the pipeline code itself, or fedora-coreos-config).
def get_push_trigger() {
    return [
        // this corresponds to the "GitHub hook trigger for GITScm polling"
        // checkbox; i.e. trigger a poll when a webhook event comes in at
        // /github-webhook/ for the repo we care about
        githubPush(),
        // but still also force poll SCM every 30m as fallback in case hooks
        // are down/we miss one
        pollSCM('H/30 * * * *')
    ]
}

// Gets desired artifacts to build from pipeline config
def get_artifacts_to_build(pipecfg, stream, basearch) {
    // Explicit stream-level override takes precedence
    def artifacts = pipecfg.streams[stream].artifacts?."${basearch}"
    if (artifacts == null) {
        // calculate artifacts from defaults + additional - skip
        artifacts = []
        artifacts += pipecfg.default_artifacts.all ?: []
        artifacts += pipecfg.default_artifacts."${basearch}" ?: []
        artifacts += pipecfg.streams[stream].additional_artifacts?.all ?: []
        artifacts += pipecfg.streams[stream].additional_artifacts?."${basearch}" ?: []
        artifacts -= pipecfg.streams[stream].skip_artifacts?.all ?: []
        artifacts -= pipecfg.streams[stream].skip_artifacts?."${basearch}" ?: []
    }
    return artifacts.unique()
}

// Build all the artifacts requested from the pipeline config for this arch.
def build_artifacts(pipecfg, stream, basearch) {

    // First get the list of artifacts to build from the config
    def artifacts = get_artifacts_to_build(pipecfg, stream, basearch)

    // Next let's do some massaging of the inputs based on two problems we
    // need to consider:
    //
    // 1. The `live` build depends on `metal` and `metal4k` artifacts to exist.
    // 2. We can't start too many parallel processes because we bump up into
    //    PID limits in the pipeline. See 24c5265. It also makes it easier to
    //    bump up against memory limits.

    // For 1. we'll sort the artifact list such that `metal` and `metal4k`
    // are at the front of the list and `live` is at the end. We'll also
    // force there to be at least two parallel runs so metal* can finish
    // before live is started..
    def maxRuns
    if (artifacts.contains('live')) {
        artifacts.remove("metal")
        artifacts.remove("metal4k")
        artifacts.remove("live")
        artifacts.add(0, "metal")
        artifacts.add(1, "metal4k")
        artifacts.add("live")

        // Define maxRuns as half of the number of artifacts. We round up here
        // because if dividing by 2 gives us 1.5 then we want to round up to 2
        // because `metal` and `metal4k` both need to go in the first run.
        // Cast to Float here because `staticMethod java.lang.Math round float`
        // is allowed but `staticMethod java.lang.Math round java.math.BigDecimal`
        // gives scriptsecurity.sandbox.RejectedAccessException.
        maxRuns = Math.round(artifacts.size().div(2) as Float) as Integer
    }

    // For 2. we'll run at most 8 tasks in parallel
    if (!maxRuns || maxRuns > 8) {
        maxRuns = 8
    }

    // Define the parallel jobs in a map
    def parallelruns = artifacts.collectEntries {
        ["💽:${it}", { shwrap("cosa buildextend-${it}") }]
    }

    // Execute!
    utils.runParallel(parallelruns, maxRuns)
}

def get_registry_repos(pipecfg, stream) {
    def registry_repos = pipecfg.registry_repos ?: [:]
    // merge top-level registry_repos with stream-specific bits
    registry_repos += pipecfg.streams[stream].additional_registry_repos ?: [:]
    return registry_repos
}

// Determine if the config.yaml has a test_architectures entry for
// this cloud which is intended to limit the architectures that
// are tested for the given cloud.
def cloud_testing_enabled_for_arch(pipecfg, cloud, basearch) {
    if (pipecfg.clouds."${cloud}"?.test_architectures) {
        // The list exists. Return true/false if the arch is in the list.
        return basearch in pipecfg.clouds."${cloud}".test_architectures
    } else {
        // If no test_architectures defined for cloud then default
        // to running on all architectures (no limits).
        return true
    }
}


// Runs followup cloud tests based on conditions
def run_cloud_tests(pipecfg, stream, version, s3_stream_dir, basearch, commit) {
    def testruns = [:]
    // Define a set of parameters that are common to all test.
    def params = [string(name: 'STREAM', value: stream),
                  string(name: 'VERSION', value: version),
                  string(name: 'ARCH', value: basearch),
                  string(name: 'SRC_CONFIG_COMMIT', value: commit)]

    // Kick off the Kola AWS job if we have an uploaded image, credentials, and testing is enabled.
    if (shwrapCapture("cosa meta --build=${version} --get-value amis") != "None" &&
        cloud_testing_enabled_for_arch(pipecfg, 'aws', basearch) &&
        utils.credentialsExist([file(variable: 'AWS_KOLA_TESTS_CONFIG',
                                     credentialsId: 'aws-kola-tests-config')])) {
        testruns['Kola:AWS'] = { build job: 'kola-aws', wait: false, parameters: params }
      // XXX: This is failing right now. Disable until the New
      // Year when someone can dig into the problem.
      //testruns['Kola:Kubernetes'] = { build job: 'kola-kubernetes', wait: false, parameters: params }
    }

    // Kick off the Kola Azure job if we have an artifact, credentials, and testing is enabled.
    if (shwrapCapture("cosa meta --build=${version} --get-value images.azure") != "None" &&
        cloud_testing_enabled_for_arch(pipecfg, 'azure', basearch) &&
        utils.credentialsExist([file(variable: 'AZURE_KOLA_TESTS_CONFIG_AUTH',
                                     credentialsId: 'azure-kola-tests-config-auth'),
                                file(variable: 'AZURE_KOLA_TESTS_CONFIG_PROFILE',
                                     credentialsId: 'azure-kola-tests-config-profile')])) {
        testruns['Kola:Azure'] = { build job: 'kola-azure', wait: false, parameters: params }
    }

    // Kick off the Kola GCP job if we have an uploaded image, credentials, and testing is enabled.
    if (shwrapCapture("cosa meta --build=${version} --get-value gcp") != "None" &&
        cloud_testing_enabled_for_arch(pipecfg, 'gcp', basearch) &&
        utils.credentialsExist([file(variable: 'GCP_KOLA_TESTS_CONFIG',
                                     credentialsId: 'gcp-kola-tests-config')])) {
        testruns['Kola:GCP'] = { build job: 'kola-gcp', wait: false, parameters: params }
    }

    // Kick off the Kola OpenStack job if we have an artifact, credentials, and testing is enabled.
    if (shwrapCapture("cosa meta --build=${version} --get-value images.openstack") != "None" &&
        cloud_testing_enabled_for_arch(pipecfg, 'openstack', basearch) &&
        utils.credentialsExist([file(variable: 'OPENSTACK_KOLA_TESTS_CONFIG',
                                     credentialsId: 'openstack-kola-tests-config')])) {
        testruns['Kola:OpenStack'] = { build job: 'kola-openstack', wait: false, parameters: params }
    }

    // Now run the kickoff jobs in parallel. These take little time
    // so there isn't much benefit in running them in parallel, but it
    // makes the UI view have less columns, which is useful.
    parallel testruns
}

// Run closure, ensuring that any xz process started inside will not go over a
// certain memory amount, which is especially useful for pods with hard set
// memory limits.
def withXzMemLimit(limitMi, Closure body) {
    withEnv(["XZ_DEFAULTS=--memlimit=${limitMi}Mi"]) {
        body()
    }
}

def get_cosa_img(pipecfg, stream) {
    // stream-level override takes precedence over top-level
    def cosa_img = pipecfg.streams[stream].cosa_img ?: pipecfg.cosa_img
    // otherwise, default to canonical cosa image
    cosa_img = cosa_img ?: 'quay.io/coreos-assembler/coreos-assembler:main'
    return utils.substituteStr(cosa_img, [STREAM: stream])
}

// Run a closure inside a context that has access to the AWS Build
// Upload credentials.
def withAWSBuildUploadCredentials(Closure body) {
    withCredentials([file(variable: 'AWS_BUILD_UPLOAD_CONFIG',
                          credentialsId: 'aws-build-upload-config')]) {
        utils.syncCredentialsIfInRemoteSession(['AWS_BUILD_UPLOAD_CONFIG'])
        body()
    }
}

// Run commands inside a context that has access to the AWS Build
// Upload credentials.
def shwrapWithAWSBuildUploadCredentials(cmds) {
    withAWSBuildUploadCredentials() {
        shwrap(cmds)
    }
}

// Return true or false based on if the AWS Build Upload credential exists
def AWSBuildUploadCredentialExists() {
    def creds = [file(variable: 'AWS_BUILD_UPLOAD_CONFIG',
                      credentialsId: 'aws-build-upload-config')]
    return utils.credentialsExist(creds)
}

// Emits Slack message if set up, otherwise does nothing.
def trySlackSend(params) {
    if (utils.credentialsExist([string(credentialsId: 'slack-api-token', variable: 'UNUSED')])) {
        slackSend(params)
    }
}

return this
