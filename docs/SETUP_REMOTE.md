# Remote Machine Setup

## Dependencies

* SSH Server
* rsync

## Users

We recommend to create a separate user per person.
There are other options like a Docker container per person though.

1. Create user.

  ```
  $ adduser {FIRST_NAME}_{LAST_NAME}
  ```

2. Place user SSH key.

  ```
  $ mkdir -p ~/.ssh
  $ chmod u+rwx,go= ~/.ssh
  $ echo {SSH_KEY} >> ~/.ssh/authorized_keys
  $ chmod u+rw,go= ~/.ssh
  ```


## Recipe

We've built a [recipe to ease the setup of remote machine](SETUP_REMOTE_RECIPE.md) for you.

# Environment variables

To start remote gradle build mirakle uses ssh which runs in non-interactive non-login mode. (Ex: `ssh user@computer ./gradlew ...`).
Depending on your remote machine operating system distribution there are may be high chances that
bash in non-interactive mode will **not read** any environment variables needed for your build from `~/.profile` or `~/.bash_profile`.
 
Ways to fix: TBD


### ANDROID_SDK_ROOT
The easiest way to define `ANDROID_SDK_ROOT` is to create file `local.properties` on remote machine and add `sdk.dir` to it. 
```
> ssh REMOTE_MACHINE
> cd PROJECT_DIR
> touch local.properties
> echo "sdk.dir=/PATH/TO/ANDROID/SDK/ON/REMOTE" > local.properties
```