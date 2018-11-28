def shwrap(cmds) {
    sh """
        set -xeuo pipefail
        cd /srv
        ${cmds}
    """
}

def shwrap_capture(cmds) {
    return sh(returnStdout: true, script: """
        set -euo pipefail
        cd /srv
        ${cmds}
    """).trim()
}

def shwrap_rc(cmds) {
    return sh(returnStatus: true, script: """
        set -euo pipefail
        cd /srv
        ${cmds}
    """)
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

    def rsync_key = readFile(file: rsync_keypath)
    rsync_key = rsync_key[0..12]

    shwrap("""
    # so we don't echo password to the jenkins logs
    set +x; export RSYNC_PASSWORD=${rsync_key}; set -x
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
