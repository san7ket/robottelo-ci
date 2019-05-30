ssh_opts='-o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null'

source config/installation_environment.conf
# Setup for the Python Code Coverage
pip install coverage

mkdir -p ${PWD}/python_coverage ; pushd ${PWD}/python_coverage/
for i in tier1 tier2 tier3 tier4 rhai destructive; do
    tar -xvf ../coverage.$i.tar
done

cat > .coveragerc <<EOF
[run]
source=
    pulp
    pulp_deb
    pulp_docker
    pulp_openstack
    pulp_ostree
    pulp_puppet
    pulp_python
    pulp_rpm

data_file=${PWD}/.coverage

parallel=true

concurrency=
    multiprocessing
    thread

[xml]
output=${PWD}/coverage.xml
EOF

coverage combine

scp $ssh_opts "${PWD}"/.coverage "root@${SERVER_HOSTNAME}:/etc/coverage/"
ssh $ssh_opts "root@${SERVER_HOSTNAME}" "cd /etc/coverage/ ; coverage report > coverage_report.txt"
ssh $ssh_opts "root@${SERVER_HOSTNAME}" "cd /etc/coverage/ ; coverage xml"

scp $ssh_opts -r "root@${SERVER_HOSTNAME}:/etc/coverage/coverage_report.txt" .
scp $ssh_opts -r "root@${SERVER_HOSTNAME}:/etc/coverage/coverage.xml" .
popd

# Setup for the Ruby Code Coverage.

if [[ "${RUBY_CODE_COVERAGE}" == "true" ]]; then

    for i in tier1 tier2 tier3 tier4 rhai destructive; do
        scp $ssh_opts tfm_reports_${i}.tar "root@${SERVER_HOSTNAME}:/root/"
    done

    for i in tier1 tier2 tier3 tier4 rhai destructive; do
        ssh $ssh_opts "root@${SERVER_HOSTNAME}" "mkdir -p /root/coverage_${i} ; tar -xvf /root/tfm_reports_${i}.tar -C /root/coverage_${i}"
    done

    wget -O merger.rb ${HTTP_SERVER_HOSTNAME}/code_coverage/ruby/merger.rb

    scp $ssh_opts merger.rb "root@${SERVER_HOSTNAME}:/root/"

    ssh $ssh_opts "root@${SERVER_HOSTNAME}" "cd /root/ ; ruby merger.rb coverage_tier1 coverage_tier2 coverage_tier3 coverage_tier4 coverage_rhai coverage_destructive"

    ssh $ssh_opts "root@${SERVER_HOSTNAME}" "cp /root/results.json /etc/coverage/ruby/tfm/reports/"
fi
