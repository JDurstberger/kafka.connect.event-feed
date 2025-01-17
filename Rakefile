require 'git'
require 'rake_circle_ci'
require 'rake_docker'
require 'rake_github'
require 'rake_gpg'
require 'rake_leiningen'
require 'rake_ssh'
require 'yaml'

require_relative 'lib/version'

version = Version.from_file('resources/VERSION')

task :default => [:'library:check', :'library:test:all']

RakeLeiningen.define_installation_tasks(
  version: '2.9.1'
)

namespace :encryption do
  namespace :directory do
    desc 'Ensure CI secrets directory exists'
    task :ensure do
      FileUtils.mkdir_p('config/secrets/ci')
    end
  end

  namespace :passphrase do
    desc 'Generate encryption passphrase for CI GPG key'
    task generate: ['directory:ensure'] do
      File.open('config/secrets/ci/encryption.passphrase', 'w') do |f|
        f.write(SecureRandom.base64(36))
      end
    end
  end
end

namespace :keys do
  namespace :deploy do
    RakeSSH.define_key_tasks(
      path: 'config/secrets/ci/',
      comment: 'maintainers@logicblocks.io'
    )
  end

  namespace :secrets do
    namespace :gpg do
      RakeGPG.define_generate_key_task(
        output_directory: 'config/secrets/ci',
        name_prefix: 'gpg',
        owner_name: 'LogicBlocks Maintainers',
        owner_email: 'maintainers@logicblocks.io',
        owner_comment: 'kafka.connect.event-feed CI Key'
      )
    end

    task generate: ['gpg:generate']
  end
end

namespace :secrets do
  task regenerate: %w[
    encryption:passphrase:generate
    keys:deploy:generate
    keys:secrets:generate
  ]
end

RakeCircleCI.define_project_tasks(
  namespace: :circle_ci,
  project_slug: 'github/logicblocks/kafka.connect.event-feed'
) do |t|
  circle_ci_config =
    YAML.load_file('config/secrets/circle_ci/config.yaml')

  t.api_token = circle_ci_config['circle_ci_api_token']
  t.environment_variables = {
    ENCRYPTION_PASSPHRASE:
      File.read('config/secrets/ci/encryption.passphrase')
      .chomp
  }
  t.checkout_keys = []
  t.ssh_keys = [
    {
      hostname: 'github.com',
      private_key: File.read('config/secrets/ci/ssh.private')
    }
  ]
end

RakeGithub.define_repository_tasks(
  namespace: :github,
  repository: 'logicblocks/kafka.connect.event-feed'
) do |t|
  github_config =
    YAML.load_file('config/secrets/github/config.yaml')

  t.access_token = github_config['github_personal_access_token']
  t.deploy_keys = [
    {
      title: 'CircleCI',
      public_key: File.read('config/secrets/ci/ssh.public')
    }
  ]
end

namespace :pipeline do
  desc 'Prepare CircleCI Pipeline'
  task prepare: %i[
    circle_ci:project:follow
    circle_ci:env_vars:ensure
    circle_ci:checkout_keys:ensure
    circle_ci:ssh_keys:ensure
    github:deploy_keys:ensure
  ]
end

namespace :library do
  RakeLeiningen.define_build_task

  RakeLeiningen.define_check_tasks(fix: true)

  namespace :test do
    RakeLeiningen.define_test_task(
      name: :unit,
      type: 'unit',
      profile: 'unit'
    )

    RakeLeiningen.define_test_task(
      name: :integration,
      type: 'integration',
      profile: 'integration'
    )

    task all: %w[unit integration]
  end

  namespace :version do
    desc 'Bump the version for the specified type, one of ' +
           ':major, :minor, :patch or :pre.'
    task :bump, [:type] => [:'leiningen:ensure'] do |_, args|
      version = version.bump(args.type)
      puts("Bumping #{args.type} part of library version. " +
             "New version is: #{version}.")
      write_version(version)
      commit_and_push("Bump version to #{version} [ci skip]")
    end

    desc 'Bump the version for a prerelease.'
    task :prerelease => [:'leiningen:ensure'] do
      version = version.prerelease
      puts("Bumping library version for prerelease. " +
             "New version is: #{version}.")
      write_version(version)
      commit_and_push("Bump version to #{version} [ci skip]")
    end

    desc 'Bump the version for a release.'
    task :release => [:'leiningen:ensure'] do
      version = version.release
      puts("Bumping library version for release. " +
             "New version is: #{version}.")
      write_version(version)
      commit_and_push("Bump version to #{version} [ci skip]")
    end
  end

  namespace :publish do
    RakeGithub.define_release_task(
      name: :prerelease,
      repository: 'logicblocks/kafka.connect.event-feed'
    ) do |t|
      github_config =
        YAML.load_file('config/secrets/github/config.yaml')

      t.access_token = github_config['github_personal_access_token']
      t.tag_name = version.to_s
      t.target_commitish = git_sha
      t.release_name = version.to_s
      t.prerelease = true
      t.assets = [
        "target/uberjar/kafka.connect.event-feed-#{version}-standalone.jar"
      ]
    end

    RakeGithub.define_release_task(
      name: :release,
      repository: 'logicblocks/kafka.connect.event-feed'
    ) do |t|
      github_config =
        YAML.load_file('config/secrets/github/config.yaml')

      t.access_token = github_config['github_personal_access_token']
      t.tag_name = version.to_s
      t.target_commitish = git_sha
      t.release_name = version.to_s
      t.prerelease = false
      t.assets = [
        "target/uberjar/kafka.connect.event-feed-#{version}-standalone.jar"
      ]
    end
  end
end

def repo
  Git.open(Pathname.new('.'))
end

def git_sha
  repo.object('HEAD').sha
end

def write_version(version)
  sh('vendor/leiningen/bin/lein ver write' +
       " :major #{version.major}" +
       " :minor #{version.minor}" +
       " :patch #{version.patch}" +
       (version.pre ? " :pre-release #{version.pre}" : "")
  )
end

def commit_and_push(message)
  repo.add
  repo.commit(message)
  repo.push('origin', 'main')
end
