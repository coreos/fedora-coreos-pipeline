import org.jenkinsci.plugins.credentialsbinding.impl.CredentialNotFoundException
import org.yaml.snakeyaml.Yaml

// Only add pipeline-specific things here. Otherwise add to coreos-ci-lib
// instead.

def load_jenkins_config() {
    return readJSON(text: shwrapCapture("""
        oc get configmap -n ${env.PROJECT_NAME} -o json jenkins-config | jq .data
    """))
}

def load_pipecfg() {
    def jenkinscfg = load_jenkins_config()
    def url = jenkinscfg['pipecfg-url']
    def ref = jenkinscfg['pipecfg-ref']

    if (url == 'in-tree') {
        return readYaml(file: "config.yaml")
    }

    // this uses the `checkout` workflow step instead of just manually cloning so
    // that changes show up in the Jenkins UI
    checkout([
        $class: 'GitSCM',
        branches: [[name: "origin/${ref}"]],
        extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'pipecfg']],
        userRemoteConfigs: [[url: url]]
    ])
    return readYaml(file: "pipecfg/config.yaml")
}

// Tells us if we're running if the official Jenkins for the FCOS pipeline
boolean isOfficial() {
    return (env.JENKINS_URL in ['https://jenkins-fedora-coreos-pipeline.apps.ocp.fedoraproject.org/'])
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

def aws_s3_cp_allow_noent(src, dest) {
    // see similar code in `cosa buildfetch`
    shwrap("""
    export AWS_CONFIG_FILE=\${AWS_FCOS_BUILDS_BOT_CONFIG}
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
        export AWS_CONFIG_FILE=\${AWS_FCOS_BUILDS_BOT_CONFIG}
        # If no remote json exists then this is the first run
        # and we'll just upload the local builds.json
        if [ -f ./remote-builds.json ]; then
            TMPD=\$(mktemp -d) && mkdir \$TMPD/builds
            mv ./remote-builds.json \$TMPD/builds/builds.json
            source /usr/lib/coreos-assembler/cmdlib.sh
            insert_build ${buildid} \$TMPD ${arch}
            mkdir -p builds
            cp \$TMPD/builds/builds.json builds/builds.json
        fi
        aws s3 cp --cache-control=max-age=300 --acl=public-read builds/builds.json s3://${s3_stream_dir}/builds/builds.json
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
    withPodmanRemote(remoteHost: "coreos-${arch}-builder-host-string",
                     remoteUid:  "coreos-${arch}-builder-uid-string",
                     sshKey:     "coreos-${arch}-builder-sshkey-key") {
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

// Runs closure if credentials exist, otherwise gracefully return.
def tryWithCredentials(creds, Closure body) {
    try {
        withCredentials(creds) {
            body()
        }
    } catch (CredentialNotFoundException e) {
        echo("${e.getMessage()}: skipping")
    }
}

// Injects the root CA cert into the cosa pod if available.
def addOptionalRootCA() {
    tryWithCredentials([file(credentialsId: 'additional-root-ca-cert', variable: 'ROOT_CA')]) {
        shwrap('''
            cp $ROOT_CA /etc/pki/ca-trust/source/anchors/
            /usr/lib/coreos-assembler/update-ca-trust-unpriv
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
    def artifacts
    if  (pipecfg.streams[stream].artifacts?."${basearch}" != null) {
        artifacts = pipecfg.streams[stream]['artifacts'][basearch]
    } else { // Merge default with additional artifacts
        def default_artifacts =  pipecfg['default_artifacts'][basearch]
        def additional_atifacts = pipecfg.streams[stream].additional_artifacts?."${basearch}"
        artifacts = default_artifacts + additional_atifacts
    }
    return artifacts.unique()
}
return this
