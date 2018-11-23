def pod
node {
    checkout scm
    pod = readFile(file: "manifests/pod.yaml")
}

def shwrap(cmds) {
    sh """
        set -xeuo pipefail
        cd /srv
        ${cmds}
    """
}

def rsync(from, to) {

    def rsync_keypath = "/var/run/secrets/kubernetes.io/duffy-key/duffy.key"
    if (!fileExists(rsync_keypath)) {
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
    rsync -avh --delete ${from}/ ${to}
    """)
}

def rsync_in(from, to) {
    rsync("fedora-coreos@artifacts.ci.centos.org::fedora-coreos/prod/${from}", "${to}")
}

def rsync_out(from, to) {
    rsync("${from}", "fedora-coreos@artifacts.ci.centos.org::fedora-coreos/prod/${to}")
}

podTemplate(cloud: 'openshift', label: 'coreos-assembler', yaml: pod, defaultContainer: 'jnlp') {
    node('coreos-assembler') { container('coreos-assembler') {
        stage('Init') {
            shwrap("""
            if [ ! -d src/config ]; then
                coreos-assembler init https://github.com/coreos/fedora-coreos-config
            fi
            """)
        }
        stage('Fetch') {
            shwrap("""
            git -C src/config pull
            coreos-assembler fetch
            """)
        }
        stage('Build') {
            shwrap("""
            coreos-assembler build
            """)
        }
        stage('Archive') {
            shwrap("""
            # Change perms to allow reading on webserver side.
            # Don't touch symlinks (https://github.com/CentOS/sig-atomic-buildscripts/pull/355)
            find builds/ ! -type l -exec chmod a+rX {} +
            find repo/   ! -type l -exec chmod a+rX {} +
            """)

            // Note that if the prod directory doesn't exist on the remote this
            // will fail. We can possibly hack around this in the future:
            // https://stackoverflow.com/questions/1636889
            rsync_out("builds", "builds")
            rsync_out("repo", "repo")
        }
    }}
}
