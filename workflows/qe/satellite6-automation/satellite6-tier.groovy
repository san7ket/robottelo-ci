@Library("github.com/SatelliteQE/robottelo-ci") _

pipeline {
 agent {
  label 'sat6-rhel'
 }
 options {
  lock(label: "${ENDPOINT}_block")
 }
 stages {
  stage('Virtualenv') {
   steps {
    make_venv python: 'python' // run with python2
   }
  }
  stage('Source Config and Variables') {
   steps {
    configFileProvider(
     [configFile(fileId: '3b0dcd98-a06a-4305-904e-db8dad82cea4', variable: 'CONFIG_FILES')]) {
     sh 'source ${CONFIG_FILES}'
     load('config/provisioning_environment.groovy')
     load('config/provisioning_env_with_endpoints.groovy')
     script {
      // Provisioning jobs TARGET_IMAGE becomes the SOURCE_IMAGE for Tier and RHAI jobs.
      // source-image at this stage for example: qe-sat63-rhel7-base
      SOURCE_IMAGE = TIER_SOURCE_IMAGE
      // target-image at this stage for example: qe-sat63-rhel7-tier1
      TARGET_IMAGE = TIER_SOURCE_IMAGE.replace('base', ENDPOINT)
      SERVER_HOSTNAME = "${TARGET_IMAGE}.${VM_DOMAIN}"
     }
    }
   }
  }
  stage("Remove older Satellite Instance") {
   steps {
    withCredentials([sshUserPrivateKey(credentialsId: 'sshUser', keyFileVariable: 'identity', passphraseVariable: '', usernameVariable: 'userName')]) {
     script {
      remote.name = "node-1"
      remote.allowAnyHosts = true
      remote.host = "10.000.000.153"
      remote.user = userName
      remote.identityFile = identity
      sshCommand remote: remote, command: "virsh destroy ${TARGET_IMAGE} || true"
      sshCommand remote: remote, command: "virsh undefine ${TARGET_IMAGE} || true"
      sshCommand remote: remote, command: "virsh vol-delete --pool default /var/lib/libvirt/images/${TARGET_IMAGE}.img || true"
     }
    }
   }
  }
  stage('Setup Satellite Instance') {

   steps {
    withCredentials([sshUserPrivateKey(credentialsId: 'sshUser', keyFileVariable: 'identity', passphraseVariable: '', usernameVariable: 'userName')]) {

     script {
      remote.name = "node-1"
      remote.allowAnyHosts = true
      remote.host = "10.000.000.153"
      remote.user = userName
      remote.identityFile = identity

      sshCommand remote: remote, command: 'snap-guest -b "${SOURCE_IMAGE}" -t "${TARGET_IMAGE}" --hostname "${SERVER_HOSTNAME}" \
    -m "${VM_RAM}" -c "${VM_CPU}" -d "${VM_DOMAIN}" -f -n bridge="${BRIDGE}" --static-ipaddr "${TIER_IPADDR}" \
    --static-netmask "${NETMASK}" --static-gateway "${GATEWAY}"'
     }
     script {
      remote.host = TIER_IPADDR
      timeout(time: 4, unit: 'MINUTES') {
       retry(120) {
        sleep(2)
        echo "Checking if box with ${TIER_IPADDR} is up yet.."
        sshCommand remote: remote, command: 'date'
       }
       echo "Box is successfully up or we have hit 4 mins timeout"
      }
     }
     script {
      //Restart Satellite6 service for a clean state of the running instance.
      def tier_name = TIER_SOURCE_IMAGE.replace('-base', '.') + VMDOMAIN
      def tier_short_name = TIER_SOURCE_IMAGE.replace('-base', '')
      remote.host = SERVER_HOSTNAME
      sshCommand remote: remote, command: "hostnamectl set-hostname ${tier_name}"
      sshCommand remote: remote, command: "sed -i '/redhat.com/d' /etc/hosts"
      sshCommand remote: remote, command: "echo ${TIER_IPADDR} ${tier_name} ${tier_short_name} >> /etc/hosts"
      sshCommand remote: remote, command: "katello-service restart"
      timeout(time: 4, unit: 'MINUTES') {
       retry(240) {
        sleep(10)
        echo "Checking if hammer ping works "
        sshCommand remote: remote, command: 'hammer ping'
       }
       echo "hammer ping is successfully up or we have hit 4 mins timeout"
      }
      script {
       // changing Satellite6 hostname (supported on Sat6.2+)
       rename_cmd = (SATELLITE_VERSION == "upstream-nightly") ? "katello-change-hostname" : "satellite-change-hostname"
       rename_cmd = "${rename_cmd} -y -u admin -p changeme "
       sshCommand remote: remote, command: rename_cmd

       if (ENDPOINT != 'tier3' && 'tier4') {
        remote.host = SERVER_HOSTNAME
        sshCommand remote: remote, command: "systemctl stop dhcpd"
       }
      }
     }
    }
   }
  }
  stage("Get robottelo"){
  steps{



  }

  }

    stage("Configure robottelo"){
steps{
            sh_venv """
                   sh scripts/
                """








    }



    }

 }
 post {
  always {
   junit(testResults: '*-results.xml', allowEmptyResults: true)
   archiveArtifacts(artifacts: '*.log', allowEmptyArchive: true)
  }
 }
}