def pod, utils, prod, prod_jenkins, devel_prefix, src_config_url, src_config_ref, s3_bucket
node {
    checkout scm
    pod = readFile(file: "manifests/pod.yaml")
    utils = load("utils.groovy")

    // just autodetect if we're in prod or not
    prod_jenkins = (env.JENKINS_URL == 'https://jenkins-fedora-coreos.apps.ci.centos.org/')
    def prod_job = (env.JOB_NAME == 'fedora-coreos/fedora-coreos-fedora-coreos-pipeline')
    prod = (prod_jenkins && prod_job)

    if (prod) {
        echo "Running in prod mode."
    } else {
        echo "Running in devel mode on ${env.JENKINS_URL}."
    }

    devel_prefix = utils.get_pipeline_annotation('devel-prefix')
    src_config_url = utils.get_pipeline_annotation('source-config-url')
    src_config_ref = utils.get_pipeline_annotation('source-config-ref')
    s3_bucket = utils.get_pipeline_annotation('s3-bucket')

    // sanity check that a valid prefix is provided if in devel mode and drop
    // the trailing '-' in the devel prefix
    if (!prod) {
      assert devel_prefix.length() > 0 : "Missing devel prefix"
      assert devel_prefix.endsWith("-") : "Missing trailing dash in devel prefix"
      devel_prefix = devel_prefix[0..-2]
    }
}

properties([
    disableConcurrentBuilds(),
    pipelineTriggers(prod ? [cron("H/30 * * * *")] : []),
    parameters([
      choice(name: 'STREAM',
             // XXX: Just pretend we're the testing stream for now... in
             // reality, we're closer to what "bodhi-updates" will be. Though the
             // testing stream is the main stream.
             choices: ['testing' /*, 'stable', 'testing-devel', 'bodhi-updates', etc... */ ],
             description: 'Fedora CoreOS stream to build',
             required: true),
      booleanParam(name: 'FORCE',
                   defaultValue: false,
                   description: 'Whether to force a rebuild')
    ])
])

// substitute the right COSA image into the pod definition before spawning it
if (prod) {
    pod = pod.replace("COREOS_ASSEMBLER_IMAGE", "coreos-assembler:master")
} else {
    pod = pod.replace("COREOS_ASSEMBLER_IMAGE", "${devel_prefix}-coreos-assembler:master")
}

podTemplate(cloud: 'openshift', label: 'coreos-assembler', yaml: pod, defaultContainer: 'jnlp') {
    node('coreos-assembler') { container('coreos-assembler') {

        // Only use the PVC for prod caching. For devel pipelines, we just
        // always refetch from scratch: we don't want to allocate cached data
        // for pipelines which may only run once.
        if (prod) {
          utils.workdir = "/srv"
        } else {
          utils.workdir = env.WORKSPACE
        }

        // this is defined IFF we *should* and we *can* upload to S3
        def s3_builddir

        if (s3_bucket && utils.path_exists("/.aws/config")) {
          if (prod) {
            // see bucket layout in https://github.com/coreos/fedora-coreos-tracker/issues/189
            s3_builddir = "${s3_bucket}/prod/streams/${params.STREAM}/builds"
          } else {
            // One prefix = one pipeline = one stream; the devel-up script is geared
            // towards testing a specific combination of (cosa, pipeline, fcos config),
            // not a full duplication of all the prod streams. One can always instantiate
            // a second prefix to test a separate combination if more than 1 concurrent
            // devel pipeline is needed.
            s3_builddir = "${s3_bucket}/devel/streams/${devel_prefix}/builds"
          }
        }

        // Special case for devel pipelines not running in our project and not
        // uploading to S3; in that case, the only way to make the builds
        // accessible at all is to have them in the PVC.
        if (!prod && !prod_jenkins && !s3_builddir) {
            utils.workdir = "/srv"
        }

        stage('Init') {

            def ref = params.STREAM
            if (src_config_ref != "") {
                ref = src_config_ref
            }

            utils.shwrap("""
            # just always restart from scratch in case it's a devel pipeline
            # and it changed source url or ref; this info also makes it into
            # the build metadata through cosa reading the origin remote
            rm -rf src/config

            # in the future, the stream will dictate the branch in the prod path
            coreos-assembler init --force --branch ${ref} ${src_config_url}
            """)
        }

        stage('Fetch') {
            if (s3_builddir) {
                utils.shwrap("""
                coreos-assembler buildprep s3://${s3_builddir}
                """)
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
            def force = params.FORCE ? "--force" : ""
            utils.shwrap("""
            coreos-assembler build --skip-prune ${force}
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

        stage('Prune Cache') {
            utils.shwrap("""
            coreos-assembler prune --keep=1
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

            if (s3_builddir) {
              // just upload as public-read for now, but see discussions in
              // https://github.com/coreos/fedora-coreos-tracker/issues/189
              utils.shwrap("""
              coreos-assembler buildupload s3 --acl=public-read ${s3_builddir}
              """)
            }

            // XXX: For now, we keep uploading the latest build to the artifact
            // server to make it easier for folks to access since we don't have
            // a stream metadata frontend/website set up yet. The key part here
            // is that it is *not* the canonical storage for builds.

            // Change perms to allow reading on webserver side.
            // Don't touch symlinks (https://github.com/CentOS/sig-atomic-buildscripts/pull/355)
            utils.shwrap("""
            find builds/ ! -type l -exec chmod a+rX {} +
            """)

            // Note that if the prod directory doesn't exist on the remote this
            // will fail. We can possibly hack around this in the future:
            // https://stackoverflow.com/questions/1636889
            if (prod) {
                utils.rsync_out("builds", "builds")
            }
        }
    }}
}
