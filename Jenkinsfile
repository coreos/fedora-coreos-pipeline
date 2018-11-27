def pod
node {
    checkout scm
    pod = readFile(file: "manifests/pod.yaml")
}

podTemplate(cloud: 'openshift', label: 'coreos-assembler', yaml: pod, defaultContainer: 'jnlp') {
    node('coreos-assembler') { container('coreos-assembler') {
        stage('Init') {
            sh """
            cd /srv/
            if [ ! -d src/config ]; then
                coreos-assembler init https://github.com/coreos/fedora-coreos-config
            fi
            """
        }
        stage('Fetch') {
            sh """
            cd /srv/src/config
            git pull
            cd /srv
            coreos-assembler fetch | tee
            """
        }
        stage('Build') {
            sh """
            cd /srv/
            coreos-assembler build | tee
            """
        }
        stage('Archive') {
            sh """
            cd /srv/
            keyfile=/var/run/secrets/kubernetes.io/duffy-key/duffy.key
            if [ ! -f \$keyfile ]; then
                echo "No \$keyfile file with rsync key."
                echo "Must be operating in dev environment"
                echo "Skipping rsync...."
                exit 0
            fi
            set +x # so we don't echo password to the jenkins logs
            RSYNC_PASSWORD=\$(cat \$keyfile)
            export RSYNC_PASSWORD=\${RSYNC_PASSWORD:0:13}
            set -x
            # Change perms to allow reading on webserver side.
            # Don't touch symlinks (https://github.com/CentOS/sig-atomic-buildscripts/pull/355)
            find builds/ ! -type l -exec chmod a+rX {} +
            find repo/   ! -type l -exec chmod a+rX {} +
            # Note that if the prod directory doesn't exist on the remote this
            # will fail. We can possibly hack around this in the future:
            # https://stackoverflow.com/questions/1636889/rsync-how-can-i-configure-it-to-create-target-directory-on-server
            rsync -avh --delete ./builds/ fedora-coreos@artifacts.ci.centos.org::fedora-coreos/prod/builds/
            rsync -avh --delete ./repo/   fedora-coreos@artifacts.ci.centos.org::fedora-coreos/prod/repo/
            """
        }
    }}
}
