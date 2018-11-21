pipeline {
  agent {
    kubernetes {
      cloud 'openshift'
      label 'coreos-assembler'
      defaultContainer 'jnlp'
      yaml """
      apiVersion: v1
      metadata:
          name: coreos-assembler
      kind: Pod
      spec:
        containers:
         - name: jnlp
           image: docker-registry.default.svc:5000/fedora-coreos/jenkins-slave-base-centos7:latest
           args: ['\$(JENKINS_SECRET)', '\$(JENKINS_NAME)']
         - name: coreos-assembler
           image: docker-registry.default.svc:5000/fedora-coreos/coreos-assembler:master
           imagePullPolicy: Always
           command: ['/usr/bin/dumb-init']
           args: ['sleep', 'infinity']
           volumeMounts:
           - name: data
             mountPath: /srv/
           - name: duffy-key
             mountPath: /var/run/secrets/kubernetes.io/duffy-key
             readOnly: true
           securityContext:
             privileged: false
        nodeSelector:
          oci_kvm_hook: allowed
        volumes:
        - name: data
          persistentVolumeClaim:
            claimName: coreos-assembler-claim
        - name: duffy-key
          secret:
            secretName: duffy.key
            optional: true
      """
    }
  }
  stages {
    stage('Init') {
      steps {
        echo "<<<< Stage: Init >>>>"
        container('coreos-assembler') {
          sh """
          cd /srv/
          if [ ! -d src/config ]; then
              coreos-assembler init https://github.com/coreos/fedora-coreos-config
          fi
          """
        }
      }
    }
    stage('Fetch') {
      steps {
        echo "<<<< Stage: Fetch >>>>"
        container('coreos-assembler') {
          sh """
          cd /srv/src/config
          git pull
          cd /srv
          coreos-assembler fetch
          """
        }
      }
    }
    stage('Build') {
      steps {
        echo "<<<< Stage: Build >>>>"
        container('coreos-assembler') {
          sh """
          cd /srv/
          coreos-assembler build | tee
          """
        }
      }
    }
    stage('Archive') {
      steps {
        echo "<<<< Stage: Archive >>>>"
        container('coreos-assembler') {
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
      }
    }
  }
}
