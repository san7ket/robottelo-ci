@Library("github.com/SatelliteQE/robottelo-ci") _

pipeline {

 agent {
  label 'sat6-rhel'
   }
 environment {
         ENDPOINT = env.ENDPOINT
         os = env.DISTRO
         SATELLITE_VERSION = env.SATELLITE_VERSION
 }
options {
  // Implement resource locking for tier jobs
  lock(label: "${ENDPOINT}_block")
  // Disable Concurrent builds
  disableConcurrentBuilds()
  // Load sauce settings
  sauceconnect(options: '', sauceConnectPath: '', useGeneratedTunnelIdentifier: true, verboseLogging: true)
 }
 stages {
  stage('Set build name and Virtualenv') {
   steps {
   cleanWs()
    make_venv python: 'python'
    script {
     currentBuild.displayName = "${currentbuild.number} ${BUILD_LABEL}"
    }
   }
  }
  stage('Source Config and Variables') {
   steps {
    configFileProvider(
     [configFile(fileId: '3b0dcd98-a06a-4305-904e-db8dad82cea4', variable: 'CONFIG_FILES')]) {
     sh_venv 'source ${CONFIG_FILES}'
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
  stage("Remove older Satellite Instance from Provisioning Host") {
   steps {
    withCredentials([sshUserPrivateKey(credentialsId: 'id_hudson_rsa', keyFileVariable: 'identity', passphraseVariable: 'pass', usernameVariable: 'userName')]) {
     script {
      remote = [: ]
      remote.name = "Provisioning server ${PROVISIONING_HOST}"
      remote.allowAnyHosts = true
      remote.host = PROVISIONING_HOST
      remote.user = userName
      remote.identityFile = identity
      sshCommand remote: remote, command: "virsh destroy ${TARGET_IMAGE} || true"
      sshCommand remote: remote, command: "virsh undefine ${TARGET_IMAGE} || true"
      sshCommand remote: remote, command: "virsh vol-delete --pool default /var/lib/libvirt/images/${TARGET_IMAGE}.img || true"
     }
    }
   }
  }
  stage('Setup Satellite Tier Instance') {

   steps {
    withCredentials([sshUserPrivateKey(credentialsId: 'id_hudson_rsa', keyFileVariable: 'identity', passphraseVariable: 'pass', usernameVariable: 'userName')]) {

     script {
      remote = [: ]
      remote.name = "Provisioning server ${PROVISIONING_HOST}"
      remote.allowAnyHosts = true
      remote.host = PROVISIONING_HOST
      remote.user = userName
      remote.identityFile = identity

      sshCommand remote: remote, command: 'snap-guest -b "${SOURCE_IMAGE}" -t "${TARGET_IMAGE}" --hostname "${SERVER_HOSTNAME}" \
    -m "${VM_RAM}" -c "${VM_CPU}" -d "${VM_DOMAIN}" -f -n bridge="${BRIDGE}" --static-ipaddr "${TIER_IPADDR}" \
    --static-netmask "${NETMASK}" --static-gateway "${GATEWAY}"'
     }
     script {
      remote = [: ]
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
      remote = [: ]
      //Restart Satellite6 service for a clean state of the running instance.
      def tier_name = TIER_SOURCE_IMAGE.replace('-base', '.') + VMDOMAIN
      def tier_short_name = TIER_SOURCE_IMAGE.replace('-base', '')
      remote.name = "Satellite server ${SERVER_HOSTNAME}"
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
      // changing Satellite6 hostname (supported on Sat6.2+)
      rename_cmd = (SATELLITE_VERSION == "upstream-nightly") ? "katello-change-hostname" : "satellite-change-hostname"
      rename_cmd = "${rename_cmd} -y -u admin -p changeme "
      sshCommand remote: remote, command: rename_cmd

      if (ENDPOINT != 'tier3' && 'tier4') {
       sshCommand remote: remote, command: "systemctl stop dhcpd"
      }
     }
    }
   }
  }
  stage("Configure robottelo according to tier instance") {
   steps {
    script {
     // Clone the robottelo repo into workspace
     checkout([$class: 'GitSCM',
      branches: [
       [name: '*/master']
      ],
      doGenerateSubmoduleConfigurations: false,
      extensions: [
       [$class: 'RelativeTargetDirectory',
        relativeTargetDir: ''
       ]
      ],
      submoduleCfg: [],
      userRemoteConfigs: [
       [url: 'https://github.com/san7ket/robottelo.git']
      ]
     ])
     // Clone Robottelo-ci repo into robottelo-ci dir
     checkout([$class: 'GitSCM',
      branches: [
       [name: '*/master']
      ],
      doGenerateSubmoduleConfigurations: false,
      extensions: [
       [$class: 'RelativeTargetDirectory',
        relativeTargetDir: 'robottelo-ci'
       ]
      ],
      submoduleCfg: [],
      userRemoteConfigs: [
       [url: 'https://github.com/san7ket/robottelo-ci.git']
      ]
     ])
     configFileProvider(
      [configFile(fileId: '3b0dcd98-a06a-4305-904e-db8dad82cea4', variable: 'CONFIG_FILES')]) {
      // Start to populate robottelo.properties file
      sh_venv '''
      source ${CONFIG_FILES}
      cp robottelo-ci/scripts/satellite6-automation.sh satellite6-automation.sh
      cp config/robottelo.properties robottelo.properties
      chmod +x satellite6-automation.sh
      sh. / satellite6-automation.sh '''
     }
    }
   }
  }
  stage("Configure and run pytest according to tier job") {
   steps {
    script {
     EXTRA_MARKS = SATELLITE_VERSION.contains("*upstream-nightly*") ? '' : "and upgrade"

     if ("${ENDPOINT}" == "destructive") {
      sh_venv 'make test-foreman-sys'
     } else if ("${ENDPOINT}" != "rhai") {
      sh_venv '''
      TEST_TYPE = "$(echo tests/foreman/{api,cli,ui,longrun,sys,installer})
    set +e
    # Run sequential tests
    $(which py.test) -v --junit-xml="${ENDPOINT}-sequential-results.xml" \
        -o junit_suite_name="${ENDPOINT}-sequential" \
        -m "${ENDPOINT} and run_in_one_thread and not stubbed ${EXTRA_MARKS}" \
        ${TEST_TYPE}

    # Run parallel tests
    $(which py.test) -v --junit-xml="${ENDPOINT}-parallel-results.xml" -n "${ROBOTTELO_WORKERS}" \
        -o junit_suite_name="${ENDPOINT}-parallel" \
        -m "${ENDPOINT} and not run_in_one_thread and not stubbed ${EXTRA_MARKS}" \
        ${TEST_TYPE}
        set -e
        '''
     } else {
      sh_venv 'make test-foreman-${ENDPOINT} PYTEST_XDIST_NUMPROCESSES=${ROBOTTELO_WORKERS}'
     }

     if ("${ROBOTTELO_WORKERS}" > 0) {
      sh_venv 'make logs-join'
      sh_venv 'make logs-clean'
     }
     echo
     echo "========================================"
     echo "Server information"
     echo "========================================"
     echo "Hostname: ${SERVER_HOSTNAME}"
     echo "Credentials: admin/changeme"
     echo "========================================"
     echo
     echo "========================================"
    }
   }
  }
 }

post {
failure{

        emailext attachLog: true,
        body: "This build ${env.BUILD_URL} is ${env.BUILD_STATUS}. If failed, may need to fix and re-trigger.",
        compressLog: true,
        subject: "The Build Number ${env.BUILD_NUMBER} of JOB ${env.JOB_NAME} is ${env.BUILD_STATUS}.', to: '${env.QE_EMAIL_LIST}'"
}


  always {
   archiveArtifacts(artifacts: '*.log,*-results.xml', allowEmptyArchive: true)
   withCredentials([sshUserPrivateKey(credentialsId: 'id_hudson_rsa', keyFileVariable: 'identity', passphraseVariable: 'pass', usernameVariable: 'userName')]) {
    script {
    junit allowEmptyResults: true,
    healthScaleFactor: 0.0,
    testDataPublishers: [[$class: 'ClaimTestDataPublisher'],
    [$class: 'StabilityTestDataPublisher']],
    testResults: '*-results.xml'
    cobertura autoUpdateHealth: false,
    autoUpdateStability: false,
    coberturaReportFile: 'coverage.xml',
    failNoReports: false,
    failUnhealthy: false,
    failUnstable: false,
    fileCoverageTargets: '10, 30, 20',
    maxNumberOfBuilds: 0,
    methodCoverageTargets: '50, 30, 40',
    onlyStable: false,
    sourceEncoding: 'ASCII',
    zoomCoverageChart: false
     remote = [: ]
     remote.name = "Satellite server"
     remote.allowAnyHosts = true
     remote.host = SERVER_HOSTNAME
     remote.user = userName
     remote.identityFile = identity
     sshCommand remote: remote, command: 'foreman-debug -s 0 -q -d "~/foreman-debug"'
     sshGet remote: remote, from: '~/foreman-debug.tar.xz', into: '.', override: true

     if (SATELLITE_DISTRIBUTION != 'UPSTREAM' || 'KOJI') {
      python_code_coverage(ENDPOINT)
     }
     if ("${RUBY_CODE_COVERAGE}" == "true") {
      ruby_code_coverage(ENDPOINT)
     }

     // Graceful shutdown for tier box
     echo "Shutting down the Base instance of ${SERVER_HOSTNAME} gracefully"
     // Shutdown the Satellite6 services before shutdown.
     sshCommand remote: remote, command: 'katello-service stop'
     // Try to shutdown the Satellite6 instance gracefully and sleep for a while.
     remote.host = PROVISIONING_HOST
     sshCommand remote: remote, command: 'virsh shutdown ${TARGET_IMAGE}'
     sshCommand remote: remote, command: 'sleep 120'
     // Destroy the sat6 instance anyways if for some reason virsh shutdown couldn't gracefully shut it down.
     sshCommand remote: remote, command: 'virsh destroy ${TARGET_IMAGE}'
     //Trigger Polarion Builds
     if ((endpoint == 'tier1' || 'tier2' || 'tier3' || 'tier4') && satellite_version.isEmpty() == False) {
      build job: "polarion-test-run-${satellite_version}-${os}",
       parameters: [
        string(name: 'TEST_RUN_ID', value: "${params.BUILD_LABEL} ${os}"),
        string(name: 'POLARION_RELEASE', value: "${params.BUILD_LABEL}"),
        string(name: 'ENDPOINT', value: "${ENDPOINT}"),
       ],
       propagate: false,
       wait: false

     }
    }
   }
  }
 }
}

def python_coverage() {
  withCredentials([sshUserPrivateKey(credentialsId: 'sshUser', keyFileVariable: 'identity', passphraseVariable: '', usernameVariable: 'userName')]) {
   script {
    remote = [: ]
    remote.name = "Satellite server"
    remote.allowAnyHosts = true
    remote.host = SERVER_HOSTNAME
    remote.user = userName
    remote.identityFile = identity
    // Shutdown the Satellite6 services for collecting coverage.
    sshCommand remote: remote, command: 'katello-service stop'
    // Create tar file for each of the Tier .coverage files to create a consolidated coverage report.
    sshCommand remote: remote, command: 'cd /etc/coverage ; tar -cvf coverage.${ENDPOINT}.tar .coverage.*'
    // Combine the coverage output to a single file and create a xml file.
    sshCommand remote: remote, command: 'cd /etc/coverage/ ; coverage combine'
    sshCommand remote: remote, command: 'cd /etc/coverage/ ; coverage xml'
    // Fetch the coverage.xml file to the project folder.
    sshGet remote: remote, from: '/etc/coverage/coverage.xml', into: '.', override: true
    // Fetch the coverage.${ENDPOINT}.tar file to the project folder.
    sshGet remote: remote, from: '/etc/coverage/coverage.${ENDPOINT}.tar', into: '.', override: true
   }
  }
 }


 def ruby_code_coverage() {
  // Create tar file for each of the Tier Coverage Report files to create a consolidated coverage report.
  sshCommand remote: remote, command: "cd /etc/coverage/ruby/tfm/reports/ ; tar -cvf /root/tfm_reports_${ENDPOINT}.tar ./."
  // Fetch the tfm_reports.${ENDPOINT}.tar file to the project folder.
  sshGet remote: remote, from: '/root/tfm_reports_${ENDPOINT}.tar', into: '.', override: true
 }