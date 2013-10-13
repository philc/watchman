# Watchman

## Developing

To deploy watchman locally to a vagrant vm:

- acquire the env.yml secrets file and place it in ansible/vars
- run the following:

        vagrant ssh-config --host watchman-vagrant >> ~/.ssh/config
        vagrant up
        ansible-playbook ansible/watchman.yml -i ansible/hosts -e 'hosts=vagrant'

- visit http://localhost:8010
