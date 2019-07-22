a = "${ENDPOINT}_block"

pipeline {
    agent { label 'master' }
    options{

             lock(label: a)
        }
    stages {
            stage('Build') {
            steps {
                configFileProvider(
                    [configFile(fileId: '3b0dcd98-a06a-4305-904e-db8dad82cea4', variable: 'CONFIG_FILES')]) {
                        sh 'source ${CONFIG_FILES}'
                        sh 'ls -ll'
                        load('config/provisioning_environment.groovy')
                        script{
                        wrap([$class: 'MaskPasswordsBuildWrapper', varPasswordPairs: [[var: 'DISTRO', password: 'asaa', var: 'a']]]) {
                            a = SOURCE_IMAGE
                            echo env['DISTRO']
                            echo env['a']
                        }

fileOperations([fileCopyOperation(excludes: '', flattenFiles: true, includes: 'config/robottelo.properties', targetLocation: 'robottelo.properties')])

                        sh 'ls -ll'
                        }
                    }
            }
    }


    stage('try ssh steps'){
        steps{
       withCredentials([sshUserPrivateKey(credentialsId: 'id_hudson_rsa', keyFileVariable: 'identity', passphraseVariable: 'pass', usernameVariable: 'userName')])
       {
           script{
            remote = [:]

            remote.allowAnyHosts = true
            remote.name = "Provisioning servers"
            remote.host = "paprika.lab.eng.rdu2.redhat.com"
            remote.user = userName
            remote.identityFile = identity
            sshCommand remote: remote, command: 'cat /tmp/b.txt || true'

           }
               script{
                   remote.host = "sesame.lab.eng.rdu2.redhat.com"
                   sshCommand remote: remote, command: 'hostname'
                   echo a
                   git defaults.robottelo_ci
                   fileOperations([fileCopyOperation(excludes: '', flattenFiles: true, includes: 'robotello-ci/satellite6-automation.sh', targetLocation: 'satellite6-automation.sh')]
               }
            )


         //sshCommand remote: remote, command: "virsh destroy ${TARGET_IMAGE} || true"
         //sshCommand remote: remote, command: "virsh undefine ${TARGET_IMAGE} || true"
         //sshCommand remote: remote, command: "virsh vol-delete --pool default /var/lib/libvirt/images/${TARGET_IMAGE}.img || true"
       }

        }


      }
}
}