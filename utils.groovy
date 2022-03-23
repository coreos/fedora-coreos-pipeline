import org.yaml.snakeyaml.Yaml

// Only add pipeline-specific things here. Otherwise add to coreos-ci-lib
// instead.

def load_config() {
    return readJSON(text: shwrapCapture("""
        oc get configmap -n ${env.PROJECT_NAME} -o json pipeline-config | jq .data
    """))
}

// Parse and handle the result of Kola
boolean checkKolaSuccess(dir, currentBuild) {
    // archive the image if the tests failed
    def report = readJSON file: "${dir}/reports/report.json"
    def result = report["result"]
    print("kola result: ${result}")
    if (result != "PASS") {
        currentBuild.result = 'FAILURE'
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
            cp \$TMPD/builds/builds.json builds/builds.json
        fi
        aws s3 cp --cache-control=max-age=300 --acl=public-read builds/builds.json s3://${s3_stream_dir}/builds/builds.json
        """)
    }
}

return this
