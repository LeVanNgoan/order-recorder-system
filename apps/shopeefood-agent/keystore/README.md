# Android signing

The public repository intentionally contains **no signing keystore** and **no signing password**.

For local/CI release signing, provide these values through Gradle properties or environment variables:

```text
ORDERRECORDER_STORE_FILE
ORDERRECORDER_STORE_PASSWORD
ORDERRECORDER_KEY_ALIAS
ORDERRECORDER_KEY_PASSWORD
```

Example local `~/.gradle/gradle.properties` (never commit this file):

```properties
ORDERRECORDER_STORE_FILE=/absolute/path/to/release.jks
ORDERRECORDER_STORE_PASSWORD=...
ORDERRECORDER_KEY_ALIAS=...
ORDERRECORDER_KEY_PASSWORD=...
```

For a public CI workflow, store the keystore as an encrypted GitHub secret, reconstruct it only inside the job, and pass the file path/passwords as job environment variables.

Debug builds do not require any repository signing material.
