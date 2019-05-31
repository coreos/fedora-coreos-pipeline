def pod, utils, devel
node {
    checkout scm
    pod = readFile(file: "manifests/pod.yaml")
    utils = load("utils.groovy")

    // just autodetect if we're in prod or not
    devel = (env.JENKINS_URL != 'https://jenkins-fedora-coreos.apps.ci.centos.org/')

    if (devel) {
        echo "Running in devel mode on ${env.JENKINS_URL}."
    } else {
        echo "Running in prod mode."
    }
}

properties([
    disableConcurrentBuilds(),
    pipelineTriggers(devel ? [] : [cron("H/30 * * * *")]),
    parameters([
      choice(name: 'STREAM',
             // XXX: Just pretend we're the testing stream for now... in
             // reality, we're closer to what "bodhi-updates" will be. Though the
             // testing stream is the main stream.
             choices: ['testing' /*, 'stable', 'testing-devel', 'bodhi-updates', etc... */ ],
             description: 'Fedora CoreOS stream to build',
             required: true)
    ])
])

podTemplate(cloud: 'openshift', label: 'coreos-assembler', yaml: pod, defaultContainer: 'jnlp') {
    node('coreos-assembler') { container('coreos-assembler') {

        stage('Init') {
            utils.shwrap("""
            # just always restart from scratch in case it's a devel pipeline
            # and it changed source url or ref; this info also makes it into
            # the build metadata through cosa reading the origin remote
            rm -rf src/config

            # in the future, the stream will dictate the branch in the prod path
            coreos-assembler init --force https://github.com/coreos/fedora-coreos-config
            """)
        }

        stage('Fetch') {
            if (!devel) {
                // make sure our cached version matches prod exactly before continuing
                utils.rsync_in("builds", "builds")
            }

            utils.shwrap("""
            coreos-assembler fetch
            """)
        }

        def prevBuildID = null
        if (utils.path_exists("builds/latest")) {
            prevBuildID = utils.shwrap_capture("readlink builds/latest")
        }

        stage('Build') {
            utils.shwrap("""
            coreos-assembler build --skip-prune
            """)
        }

        def newBuildID = utils.shwrap_capture("readlink builds/latest")
        if (prevBuildID == newBuildID) {
            currentBuild.result = 'SUCCESS'
            currentBuild.description = "💤 (no new build)"
            return
        } else {
            currentBuild.description = "⚡ ${newBuildID}"
        }

        stage('Build Metal') {
            utils.shwrap("""
            coreos-assembler buildextend-metal
            """)
        }

        stage('Build Installer') {
            utils.shwrap("""
            coreos-assembler buildextend-installer
            """)
        }

        stage('Build Openstack') {
            utils.shwrap("""
            coreos-assembler buildextend-openstack
            """)
        }

        stage('Build VMware') {
            utils.shwrap("""
            coreos-assembler buildextend-vmware
            """)
        }

        stage('Prune') {
            utils.shwrap("""
            coreos-assembler prune --keep=8
            """)

            // If the cache img is larger than e.g. 8G, then nuke it. Otherwise
            // it'll just keep growing and we'll hit ENOSPC.
            utils.shwrap("""
            if [ \$(du cache/cache.qcow2 | cut -f1) -gt \$((1024*1024*8)) ]; then
                rm -vf cache/cache.qcow2
                qemu-img create -f qcow2 cache/cache.qcow2 10G
                LIBGUESTFS_BACKEND=direct virt-format --filesystem=xfs -a cache/cache.qcow2
            fi
            """)
        }

        stage('Archive') {

            // First, compress image artifacts
            utils.shwrap("""
            coreos-assembler compress
            """)

            // Change perms to allow reading on webserver side.
            // Don't touch symlinks (https://github.com/CentOS/sig-atomic-buildscripts/pull/355)
            utils.shwrap("""
            find builds/ ! -type l -exec chmod a+rX {} +
            """)

            // Note that if the prod directory doesn't exist on the remote this
            // will fail. We can possibly hack around this in the future:
            // https://stackoverflow.com/questions/1636889
            if (!devel) {
                utils.rsync_out("builds", "builds")
            }
        }
    }}
}
