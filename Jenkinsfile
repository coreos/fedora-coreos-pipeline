def pod, utils
node {
    checkout scm
    pod = readFile(file: "manifests/pod.yaml")
    utils = load("utils.groovy")
}

podTemplate(cloud: 'openshift', label: 'coreos-assembler', yaml: pod, defaultContainer: 'jnlp') {
    node('coreos-assembler') { container('coreos-assembler') {
        stage('Init') {
            utils.shwrap("""
            if [ ! -d src/config ]; then
                coreos-assembler init https://github.com/coreos/fedora-coreos-config
            fi
            """)
        }
        stage('Fetch') {
            utils.shwrap("""
            git -C src/config pull
            coreos-assembler fetch
            """)
        }
        stage('Build') {
            utils.shwrap("""
            coreos-assembler build
            """)
        }
        stage('Archive') {
            utils.shwrap("""
            # Change perms to allow reading on webserver side.
            # Don't touch symlinks (https://github.com/CentOS/sig-atomic-buildscripts/pull/355)
            find builds/ ! -type l -exec chmod a+rX {} +
            find repo/   ! -type l -exec chmod a+rX {} +
            """)

            // Note that if the prod directory doesn't exist on the remote this
            // will fail. We can possibly hack around this in the future:
            // https://stackoverflow.com/questions/1636889
            utils.rsync_out("builds", "builds")
            utils.rsync_out("repo", "repo")
        }
    }}
}
