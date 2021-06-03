@Library('github.com/coreos/coreos-ci-lib@main') _

import org.yaml.snakeyaml.Yaml

// Only add pipeline-specific things here. Otherwise add to coreos-ci-lib
// instead.

def get_annotation(bc, anno) {
    def bcYaml = readYaml(text: shwrapCapture("oc get buildconfig ${bc} -n ${env.PROJECT_NAME} -o yaml"))
    return bcYaml['metadata']['annotations']["coreos.com/${anno}"]
}

def get_pipeline_annotation(anno) {
    // should probably cache this, but meh... I'd rather
    // hide this goop here than in the main pipeline code
    def split = env.JOB_NAME.split('/')
    def namespace = split[0]
    def bc = split[1][namespace.length()+1..-1]
    return get_annotation(bc, anno)
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

return this
