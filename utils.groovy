def shwrap(cmds) {
    sh """
        set -xeuo pipefail
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

return this
