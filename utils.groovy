@Library('github.com/coreos/coreos-ci-lib@main') _

import org.yaml.snakeyaml.Yaml

// Only add pipeline-specific things here. Otherwise add to coreos-ci-lib
// instead.

def get_annotation(anno) {
    // This works off the assumption that either there's only one
    // buildconfig in the project that has the annotations we're looking
    // for OR the annotation value is the same for all BCs that have it.
    //
    // Explanation:
    //  - starts with [] to generate a list
    //  - iterates over the items[] and selects the metadata.annotations
    //  - in the given objects finds the value of "coreos.com/${anno}"
    //  - filters out null values (objects without given annotation)
    //  - trims it down to a list of unique entries (should be single item in list)
    //  - selects first item in list [0]
    return shwrapCapture("""
        oc get buildconfig -n ${env.PROJECT_NAME} -o json | \
            jq -r '[.items[].metadata.annotations | .["coreos.com/${anno}"] | select(. != null)] | unique'[0]
    """)
}

def get_pipeline_annotation(anno) {
    // should probably cache this, but meh... I'd rather
    // hide this goop here than in the main pipeline code
    return get_annotation(anno)
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
    // see similar code in `cosa buildprep`
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
        aws s3 cp --acl=public-read builds/builds.json s3://${s3_stream_dir}/builds/builds.json
        """)
    }
}

return this
