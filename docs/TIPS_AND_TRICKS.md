# Tips and Tricks

- [Speed up Android build by breaking remote execution on `package` task](#speed-up-android-build-by-breaking-remote-execution-on-package-task)
- [Pass break task pattern via command line argument](#pass-break-task-pattern-via-command-line-argument)
- [Show list of updates made by rsync (deletions, uploads, downloads)](#show-list-of-updates-made-by-rsync-deletions-uploads-downloads)
- [Show total transfer progress](#show-total-transfer-progress)
- [Set Gradle properties for remote build using mirakle.properties and mirakle_local.properties](#set-gradle-properties-for-remote-build-using-mirakleproperties-and-mirakle_localproperties)
- [Determine if build occurs on remote machine](#determine-if-build-occurs-on-remote-machine)
- [Print Gradle arguments of remote build](#print-gradle-arguments-of-remote-build)

Contribution is welcome!

### Speed up Android build by breaking remote execution on `package` task 

The main result of Android application build is `.apk` file which contains all the code and resources of the application.
 `.apk` file is a zip archive that can't be downloaded from remote machine by rsync incrementally.
 Making just a small fix to codebase or adding new resource will lead to the need of downloading the whole file the size of which can be quite large.
 
The idea of breaking remote building process on `package` task is to stop remote build one step before archiving all the application files into `.apk` file 
and let rsync incrementally download only these files that changed during the build.
One line fix usually affects one `.dex` file whose size is significantly less than size of whole `.apk` file.
 
Mirakle will download all the inputs of `package` task and execute it by Gradle on local machine.        
      
```groovy
initscript {
    repositories {
        jcenter()
    }
    dependencies {
        classpath "com.instamotor:mirakle:1.4.0"
    }
}
 
apply plugin: MirakleBreakMode // <- 
 
rootProject {
    mirakle {
        host "your_remote_machine"
        breakOnTasks += ["package"] // this is regex pattern that matches all build flavour variations of package task.  
        excludeRemote += ["build/tmp", "build/generated", "build/intermediates/*", "build/kotlin"]
        rsyncFromRemoteArgs += ["-i", "-c", "--compress-level=2"] 
    }
}
```

```
> ./gradlew assembleStagingDebug

:uploadToRemote
:executeOnRemote
Mirakle will break remote execution on task ':app:packageStagingDebug'
:downloadFromRemote
>fcst....... app/build/intermediates/dex/stagingDebug/mergeDexStagingDebug/classes2.dex
app:packageStagingDebug

Task uploadToRemote took : 0.19 secs
Task executeOnRemote took : 8.08 secs
Task downloadFromRemote took : 1.065 secs
Task app:packageStagingDebug took : 1.362 secs
```
#### Pass break task pattern via command line argument
```
> ./gradlew assembleStagingDebug --project-prop mirakle.break.task=package
```

### Show list of updates made by rsync (deletions, uploads, downloads)

```groovy
mirakle {
    rsyncToRemoteArgs += ["-i"] 
    rsyncFromRemoteArgs += ["-i"] 
}
```
```
> ./gradlew assembleStagingDebug

:uploadToRemote
.d..t....... app/src/main/
<f.st....... app/src/main/AndroidManifest.xml
:executeOnRemote
:downloadFromRemote
>f..t....... app/build/outputs/apk/staging/debug/output-metadata.json
>f.st....... app/build/outputs/apk/staging/debug/android_app-staging-debug.apk
.d..t....... app/build/outputs/logs/
>f++++++++++ app/build/outputs/logs/manifest-merger-staging-debug-report.txt
```

Explanation of each bit position and value in rsync‘s `-i` output:
```
YXcstpoguax  path/to/file
|||||||||||
||||||||||╰- x: The extended attribute information changed
|||||||||╰-- a: The ACL information changed
||||||||╰--- u: The u slot is reserved for future use
|||||||╰---- g: Group is different
||||||╰----- o: Owner is different
|||||╰------ p: Permission are different
||||╰------- t: Modification time is different
|||╰-------- s: Size is different
||╰--------- c: Different checksum (for regular files), or
||              changed value (for symlinks, devices, and special files)
|╰---------- the file type:
|            f: for a file,
|            d: for a directory,
|            L: for a symlink,
|            D: for a device,
|            S: for a special file (e.g. named sockets and fifos)
╰----------- the type of update being done::
             <: file is being transferred to the remote host (sent)
             >: file is being transferred to the local host (received)
             c: local change/creation for the item, such as:
                - the creation of a directory
                - the changing of a symlink,
                - etc.
             h: the item is a hard link to another item (requires 
                --hard-links).
             .: the item is not being updated (though it might have
                attributes that are being modified)
             *: means that the rest of the itemized-output area contains
                a message (e.g. "deleting")

```
Examples:
```
>f.st......

    > - the item is received
    f - it is a regular file
    s - the file size is different
    t - the time stamp is different

.d..t......

    . - the item is not being updated (though it might have attributes 
        that are being modified)
    d - it is a directory
    t - the time stamp is different

>f+++++++++
    
    > - the item is received
    f - a regular file
    +++++++++ - this is a newly created item
```

### Show total transfer progress
```groovy
mirakle {
    rsyncToRemoteArgs += ["--info=progress2"] 
    rsyncFromRemoteArgs += ["--info=progress2"] 
}
```
```
> ./gradlew assembleStagingDebug

:uploadToRemote
:executeOnRemote
:downloadFromRemote
     66,775,594  69%   17.77MB/s    0:00:03 (xfr#2, to-chk=0/40)
```
Other available `--info` flags are:
```
Use OPT or OPT1 for level 1 output, OPT2 for level 2, etc.; OPT0 silences.

BACKUP     Mention files backed up
COPY       Mention files copied locally on the receiving side
DEL        Mention deletions on the receiving side
FLIST      Mention file-list receiving/sending (levels 1-2)
MISC       Mention miscellaneous information (levels 1-2)
MOUNT      Mention mounts that were found or skipped
NAME       Mention 1) updated file/dir names, 2) unchanged names
PROGRESS   Mention 1) per-file progress or 2) total transfer progress
REMOVE     Mention files removed on the sending side
SKIP       Mention files that are skipped due to options used
STATS      Mention statistics at end of run (levels 1-3)
SYMSAFE    Mention symlinks that are unsafe

ALL        Set all --info options (e.g. all4)
NONE       Silence all --info options (same as all0)
HELP       Output this help message

Options added for each increase in verbose level:
1) COPY,DEL,FLIST,MISC,NAME,STATS,SYMSAFE
2) BACKUP,MISC2,MOUNT,NAME2,REMOVE,SKIP
```

### Set Gradle properties for remote build using `mirakle.properties` and `mirakle_local.properties`
`PROJECT_DIR/mirakle.properties` and `PROJECT_DIR/mirakle_local.properties` can be used to pass specific Gradle properties to remote build or override existing properties.
Properties from that files have highest priority over all other properties.
 
Both files serves the same purpose, but `mirakle_local.properties` may be used to define user specific values and skipped from committing to VCS.

### Determine if build occurs on remote machine
Put it in `build.gradle` in project dir:
```
if (project.hasProperty('mirakle.build.on.remote')) {
    ...
}
```


### Print Gradle arguments of remote build
Put this into `USER_HOME/.gradle/init.d/mirakle_init.gradle` or `PROJECT_DIR/mirakle.gradle` on local machine:
```
taskGraph.whenReady { taskGraph ->
    if (taskGraph.hasTask(":executeOnRemote")) {
        gradle.rootProject.executeOnRemote.doFirst {
            println("============REMOTE GRADLE ARGUMENTS===============")
            println(rootProject.executeOnRemote.args.drop(2).join("\n"))
            println("============REMOTE GRADLE ARGUMENTS===============")
        }
    }
}
```
