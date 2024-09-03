node {
    checkout scm
    // these are script global vars
    pipeutils = load("utils.groovy")
    pipecfg = pipeutils.load_pipecfg()
}

properties([
    pipelineTriggers([]),
    parameters([
      choice(name: 'STREAM',
             choices: pipeutils.get_streams_choices(pipecfg),
             description: 'CoreOS stream to test'),
      string(name: 'START_VERSION',
             description: 'CoreOS Build ID or Fedora major version to start from',
             trim: true),
      string(name: 'TARGET_VERSION',
             description: 'Final CoreOS Build ID that passes test',
             defaultValue: '',
             trim: true),
      string(name: 'ARCH',
             description: 'Target architecture',
             defaultValue: 'x86_64',
             trim: true),
      string(name: 'COREOS_ASSEMBLER_IMAGE',
             description: 'Override the coreos-assembler image to use',
             defaultValue: "quay.io/coreos-assembler/coreos-assembler:main",
             trim: true),
      string(name: 'SRC_CONFIG_COMMIT',
             description: 'The exact config repo git commit to run tests against',
             defaultValue: '',
             trim: true),
    ]),
    buildDiscarder(logRotator(
        numToKeepStr: '100',
        artifactNumToKeepStr: '100'
    )),
    durabilityHint('PERFORMANCE_OPTIMIZED')
])


// Map the FCOS stream name to the production stream we should
// use for the test.
def start_streams = [
    'stable':         'stable',
    'testing':        'testing',
    'next':           'next',
    'testing-devel':  'testing',
    'next-devel':     'next',
    'branched':       'next',
    'rawhide':        'next',
]

// Deadend releases as found in
// https://github.com/coreos/fedora-coreos-streams/tree/main/updates
def deadends = ['38.20230310.1.0','30.20190716.1']

// Vars for start/target versions
def target_version = params.TARGET_VERSION
def start_version = params.START_VERSION
def start_stream = start_streams[params.STREAM]

// Because of an x86_64 bootloader issue the oldest that is supported
// on x86_64 is 32.x: https://github.com/coreos/fedora-coreos-tracker/issues/1448
if (start_version == '' && params.ARCH == 'x86_64') {
    start_version = '32'
}

currentBuild.description = "[${params.STREAM}][${params.ARCH}] - ${start_version}->${target_version}"

// Set the memory request to a reasonable value.
def cosa_memory_request_mb = 1024
if (params.ARCH == 'x86_64') {
    // local (qemu+x86_64) testing will require more memory
    // bios=1024, uefi=1024, uefi-secure=1536, overhead=512
    cosa_memory_request_mb = 1024 + 1024 + 1536 + 512
}

lock(resource: "kola-upgrade-${params.ARCH}") {
    cosaPod(memory: "${cosa_memory_request_mb}Mi",
            image: params.COREOS_ASSEMBLER_IMAGE,
            serviceAccount: "jenkins") {
    timeout(time: 90, unit: 'MINUTES') {
    try {

        // Determine the target version. If no params.TARGET_VERSION was
        // specified then it will be the latest in the params.STREAM.
        if (target_version == '') {
            target_version = shwrapCapture("""
            curl -L https://builds.coreos.fedoraproject.org/prod/streams/${params.STREAM}/builds/builds.json | \
                jq -r .builds[0].id
            """)
        }
        echo "Selected ${target_version} as the target version to test"
        // Determine the start version based on the provided params.START_VERSION
        // and the releases.json for this stream. The user can provide a full
        // version, the empty string (implies earliest available), or two digits
        // (implies earliest available based on the Fedora major).
        if (start_version.length() > 2) {
            if (start_version in deadends) {
                error("Specified start_version is a deadend release")
            }
        } else {
            shwrap("curl -LO https://builds.coreos.fedoraproject.org/prod/streams/${start_stream}/releases.json")
            def releases = readJSON file: "releases.json"
            def newest_version = releases["releases"][-1]["version"]
            for (release in releases["releases"]) {
                def has_arch = release["commits"].find{ commit -> commit["architecture"] == params.ARCH }
                if (release["version"] in deadends || has_arch == null) {
                    continue // This release has been disqualified
                }
                if (start_version.length() == 2) {
                    if (release["version"] == newest_version) {
                        // We've reached the latest build in the stream. This can happen
                        // when we're testing i.e. rawhide and it's moved on to FN+1, but
                        // `next` hasn't. Just use the newest build in `start_stream` in
                        // that case.
                        start_version = newest_version
                        break
                    } else if ((release["version"][0..1] as Integer) > (start_version as Integer)) {
                        echo "There wasn't a release for this architecture for Fedora ${start_version}.. Skipping"
                        return
                    } else if (release["version"][0..1] == start_version) {
                        start_version = release["version"]
                        break
                    }
                } else {
                    // No restrictions on start_version. Use oldest available
                    start_version = release["version"]
                    break
                }
            }
        }
        echo "Selected ${start_version} as the starting version to test"
        currentBuild.description = "[${params.STREAM}][${params.ARCH}] - ${start_version}->${target_version}"

        def remoteSession = ""
        if (params.ARCH != 'x86_64') {
            // If we're on mArch and using QEMU then initialize the
            // session on the remote builder
            stage("Initialize Remote") {
                pipeutils.withPodmanRemoteArchBuilder(arch: params.ARCH) {
                    remoteSession = shwrapCapture("""
                    cosa remote-session create --image ${params.COREOS_ASSEMBLER_IMAGE} --expiration 4h --workdir ${env.WORKSPACE}
                    """)
                }
            }
        }

        // Run the remaining code in a remote session if we created one.
        pipeutils.withOptionalExistingCosaRemoteSession(
                        arch: params.ARCH, session: remoteSession) {
            stage('BuildFetch') {
                def commitopt = ''
                if (params.SRC_CONFIG_COMMIT != '') {
                    commitopt = "--commit=${params.SRC_CONFIG_COMMIT}"
                }
                def ref = pipeutils.get_source_config_ref_for_stream(pipecfg, params.STREAM)
                pipeutils.shwrapWithAWSBuildUploadCredentials("""
                cosa init --force --branch ${ref} ${commitopt} ${pipecfg.source_config.url}
                cosa buildfetch --artifact=qemu --stream=${start_stream} --build=${start_version} --arch=${params.ARCH}
                cosa decompress --build=${start_version}
                """)
            }

            // A few independent tasks that can be run in parallel
            def parallelruns = [:]

            shwrap("""
            cosa shell -- tee tmp/target_stream.bu <<EOF
variant: fcos
version: 1.0.0
storage:
  files:
    - path: /etc/target_stream
      mode: 0644
      contents:
        inline: |
          ${params.STREAM}
EOF
""")

            def kolaparams = [
                arch: params.ARCH,
                build: start_version,
                cosaDir: env.WORKSPACE,
                extraArgs: "--tag extended-upgrade --append-butane tmp/target_stream.bu",
                skipBasicScenarios: true,
                skipUpgrade: true,
                skipKolaTags: pipecfg.streams[params.STREAM].skip_kola_tags,
            ]
            def k1, k2, k3

            switch(params.ARCH) {
                case 'x86_64':
                    k1 = kolaparams.clone()
                    k1.extraArgs += " --qemu-firmware=uefi"
                    k1.marker = "uefi"
                    parallelruns['Kola:UEFI'] = { kola(k1) }
                    // SecureBoot doesn't work on older FCOS builds with latest qemu
                    // so we must run it conditionally.
                    // https://github.com/coreos/fedora-coreos-tracker/issues/1452
                    def secureboot_start_version = 34
                    if (start_stream == 'next') {
                        secureboot_start_version = 35
                    }
                    if ((start_version[0..1] as Integer) >= secureboot_start_version) {
                        k2 = kolaparams.clone()
                        k2.extraArgs += " --qemu-firmware=uefi-secure"
                        if ((start_version[0..1] as Integer) <= 37) {
                            // workaround a bug where grub would fail to allocate memory
                            // when start_version is <= 37.20230110.2.0
                            // https://github.com/coreos/fedora-coreos-tracker/issues/1456
                            k2.extraArgs += " --qemu-memory=1536"
                        }
                        k2.marker = "uefi-secure"
                        parallelruns['Kola:UEFI-SECURE'] = { kola(k2) }
                    }
                    k3 = kolaparams.clone()
                    k3.extraArgs += " --qemu-firmware=bios"
                    k3.marker = "bios"
                    parallelruns['Kola:BIOS'] = { kola(k3) }
                    break;
                case 'aarch64':
                    k1 = kolaparams.clone()
                    k1.extraArgs += " --qemu-firmware=uefi"
                    k1.marker = "uefi"
                    parallelruns['Kola:UEFI'] = { kola(k1) }
                    break;
                case 's390x':
                    parallelruns['Kola'] = { kola(kolaparams) }
                    break;
                case 'ppc64le':
                    parallelruns['Kola'] = { kola(kolaparams) }
                    break;
                default:
                    assert false
                    break;
            }

            // process this batch
            parallel parallelruns
        }

        // Destroy the remote sessions. We don't need them anymore
        if (remoteSession != "") {
            stage("Destroy Remote") {
                pipeutils.withExistingCosaRemoteSession(
                    arch: params.ARCH, session: remoteSession) {
                    shwrap("cosa remote-session destroy")
                }
            }
        }
        currentBuild.result = 'SUCCESS'

    } catch (e) {
        currentBuild.result = 'FAILURE'
        throw e
    } finally {
        if (currentBuild.result != 'SUCCESS') {
            pipeutils.trySlackSend(message: "kola-upgrade ${env.BUILD_NUMBER} <${env.BUILD_URL}|:jenkins:> <${env.RUN_DISPLAY_URL}|:ocean:> [${params.STREAM}][${params.ARCH}] (${start_version}->${target_version})")
        }
    }
}}} // lock, cosaPod and timeout finish here
