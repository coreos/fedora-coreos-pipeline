def shwrap(cmds) {
    sh """
        set -xeuo pipefail
        ${cmds}
    """
}

// Useful when we don't want to show confidential information in logs
def shwrap_quiet(cmds) {
    sh """
        set +x -euo pipefail
        ${cmds}
    """
}

def shwrap_capture(cmds) {
    return sh(returnStdout: true, script: """
        set -euo pipefail
        ${cmds}
    """).trim()
}

def shwrap_rc(cmds) {
    return sh(returnStatus: true, script: """
        set -euo pipefail
        ${cmds}
    """)
}

def get_pipeline_annotation(anno) {
    // should probably cache this, but meh... I'd rather
    // hide this goop here than in the main pipeline code
    def split = env.JOB_NAME.split('/')
    def namespace = split[0]
    def bc = split[1][namespace.length()+1..-1]
    def annopath = "{.metadata.annotations.coreos\\\\.com/${anno}}"
    return shwrap_capture(
      "oc get buildconfig ${bc} -n ${namespace} -o=jsonpath=${annopath}")
}

// This is like fileExists, but actually works inside the Kubernetes container.
def path_exists(path) {
    return shwrap_rc("test -e ${path}") == 0
}

// Parse and handle the result of Kola
boolean checkKolaSuccess(dir, currentBuild) {
    // archive the image if the tests failed
    def report = readJSON file: "${dir}/reports/report.json"
    def result = report["result"]
    print("kola result: ${result}")
    if (result != "PASS" && report["platform"] == "qemu-unpriv") {
        shwrap("coreos-assembler compress --compressor xz")
        archiveArtifacts "builds/latest/**/*.qcow2.xz"
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
