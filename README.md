AI Advent Challenge

## Build Troubleshooting

- If dependency resolution fails with HTTP 403 for `org.jetbrains.compose` or `org.jetbrains.androidx` artifacts, ensure the JetBrains Compose Maven repository (`https://maven.pkg.jetbrains.space/public/p/compose/dev`) is present in `settings.gradle.kts` plugin and dependency resolution blocks.
- For a fresh workspace run `./gradlew --no-configuration-cache clean :composeApp:run` to avoid configuration cache issues while Compose plugins warm up.
