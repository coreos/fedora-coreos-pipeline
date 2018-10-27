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
           image: docker-registry.default.svc:5000/fedora-coreos/dustymabe-coreos-assembler:latest
           imagePullPolicy: Always
           command: ['/bin/bash']
           args: ['-c', 'sleep infinity']
           volumeMounts:
           - name: data
             mountPath: /srv/
           - name: duffy-key
             mountPath: /var/run/secrets/kubernetes.io/duffy-key
             readOnly: true
           securityContext:
             privileged: false
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
    stage('Git Pull') {
      steps {
        echo "<<<< Stage: Git Pull >>>>"
        container('coreos-assembler') {
          sh """
          cd /srv/src/config 
          git pull
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
          rsync -avh --delete ./builds/ fedora-coreos@artifacts.ci.centos.org::fedora-coreos/delete/builds/
          rsync -avh --delete ./repo/ fedora-coreos@artifacts.ci.centos.org::fedora-coreos/delete/repo/
          """
        }
      }
    }
  }
}
