# Watchman

Details coming soon.

## Getting started

    lein lobos migrate
    bin/run_app.sh

## Developing

To run the tests:

    lein midje

* For UI changes, use the classic workflow: modify the source and refresh the browser.
* For non-UI changes, running code via the REPL is the fastest way to test and iterate. Sending emails and
  polling checks requires a bit of state to exercise. The `dev-sandbox` namespace has a few functions to
  quickly create some state for testing/development, and some commonly-used code for sending emails, for
  instance.

## Deployment

To deploy Watchman locally to a Vagrant VM:

- Flesh out the yaml file in ansible/group_vars/vagrant to suit your needs.
- Run the following to setup vagrant:

        vagrant up
        vagrant ssh-config --host watchman-vagrant >> ~/.ssh/config
        ansible-playbook ansible/watchman.yml -i ansible/hosts -e 'hosts=vagrant'

- Visit http://localhost:8010. The vagrant VM's port 80 is forwarded over 8010.
