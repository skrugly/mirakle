# Contributing

1. Build the plugin and install it to local maven repo
    
    `./gradlew mirakle:build mirakle:install -x mirakle`
2. Add `mavenLocal()` to Mirakle's init script

    ```
    initscript {
         repositories {
             mavenLocal()
             jcenter()
         }
     }
     ```
3. Make sure your init script uses the same Mirakle version that you defined in root [build.gradle](https://github.com/Instamotor-Labs/mirakle/blob/development/build.gradle#L2). Increment it just in case.
4. Mirakle is targeting `localhost` in tests, so make sure you can `ssh localhost` without password. If not:
- If it saying about `Connection refused` for Mac OSX read [this](https://stackoverflow.com/a/22255174), for Linux google...
- If it required password do [this](https://stackoverflow.com/a/16651742)
5. Run the tests

    `./gradlew plugin-test:test -x mirakle`
