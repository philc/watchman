Vagrant.configure("2") do |config|
  config.vm.box = "precise64"
  config.vm.box_url = "http://files.vagrantup.com/precise64.box"
  config.vm.host_name = "watchman-vagrant"
  config.vm.network "forwarded_port", guest: 80, host: 8010
  config.vm.network "forwarded_port", guest: 22, host: 2210
  config.ssh.port = 2210
end
