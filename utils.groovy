workdir = env.WORKSPACE

def shwrap(cmds) {
    sh """
        set -xeuo pipefail
        cd ${workdir}
        ${cmds}
    """
}

def shwrap_capture(cmds) {
    return sh(returnStdout: true, script: """
        set -euo pipefail
        cd ${workdir}
        ${cmds}
    """).trim()
}

def shwrap_rc(cmds) {
    return sh(returnStatus: true, script: """
        set -euo pipefail
        cd ${workdir}
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

def rsync(from, to) {

    def rsync_keypath = "/var/run/secrets/kubernetes.io/duffy-key/duffy.key"
    if (!path_exists(rsync_keypath)) {
        echo "No ${rsync_keypath} file with rsync key."
        echo "Must be operating in dev environment"
        echo "Skipping rsync...."
        return
    }

    shwrap("""
    # so we don't echo password to the jenkins logs
    set +x
    RSYNC_PASSWORD=\$(cat ${rsync_keypath})
    export RSYNC_PASSWORD=\${RSYNC_PASSWORD:0:13}
    set -x
    # always add trailing slash for consistent semantics
    rsync -ah --stats --delete ${from}/ ${to}
    """)
}

def rsync_in(from, to) {
    rsync("fedora-coreos@artifacts.ci.centos.org::fedora-coreos/prod/${from}", "${to}")
}

def rsync_out(from, to) {
    rsync("${from}", "fedora-coreos@artifacts.ci.centos.org::fedora-coreos/prod/${to}")
}

return this
