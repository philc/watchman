# Watchman

Details coming soon.

## Developing

To deploy Watchman locally to a Vagrant VM:

- Flesh out the yaml file in ansible/group_vars/vagrant to suit your needs.
- Run the following to setup vagrant:

        vagrant up
        vagrant ssh-config --host watchman-vagrant >> ~/.ssh/config
        ansible-playbook ansible/watchman.yml -i ansible/hosts -e 'hosts=vagrant'

- Visit http://localhost:8010. The vagrant VM's port 80 is forwarded over 8010.
