# Mirakle
A Gradle plugin that allows you to move build process from a local machine to a remote one.

Compatible with Gradle 3.3+. Works seamlessly with IntelliJ IDEA and Android Studio.

#### Why
Remote machine supposed to be much performant than you working machine.
Also having a sufficient network bandwidth or small amount of data that your build produce, you gain **build speed boost**.

#### How it differs from [Mainframer](https://github.com/gojuno/mainframer)

Mirakle is designed specially for Gradle build system. It works as seamless as possible. Once plugin installed, you workflow will not be different at all.

(It's a good thing to prank your colleague. Imagine his surprise when one day he get several times faster build time.)


## Setup
* Put this into `USER_HOME/.gradle/init.d/mirakle.gradle`
```groovy
initscript {
    repositories {
        jcenter()
    }
    dependencies {
        classpath "com.instamotor:mirakle:1.0.0"
    }
}
 
apply plugin: Mirakle
 
rootProject {
    mirakle {
        host "your_remote_machine"
    }
}
```
* [Setup local machine](docs/SETUP_LOCAL.md)
* [Setup remote machine](docs/SETUP_REMOTE.md)

## Usage
```
> ./gradlew build

Here's Mirakle. All tasks will be executed on a remote machine.
:uploadToRemote
:executeOnRemote
...remote build output...
:downloadFromRemote
 

BUILD SUCCESSFUL
 
Total time : 7.975 secs
Task uploadToRemote took: 2.237 secs
Task executeOnRemote took: 3.706 secs
Task downloadFromRemote took: 1.597 secs
```

To disable remote execution pass `-x mirakle`

```
> ./gradlew build install -x mirakle
```

## Configuration
```groovy
mirakle {
    host "true-remote"
    
    //optional
    remoteFolder ".mirakle"
    excludeLocal += ["foo"]
    excludeRemote += ["bar"]
    rsyncToRemoteArgs += ["--stats", "-h"]
    rsyncFromRemoteArgs += ["--compress-level=5", "--stats", "-h"]
    sshArgs += ["-p 22"]
}
```
## [Contributing](docs/CONTRIBUTING.md)

## License
```
Copyright 2017 Instamotor, Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
