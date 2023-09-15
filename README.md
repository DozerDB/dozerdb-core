# README


## About 
DozerDb enhances Neo4j core / AKA Neo4j Community Edition with enterprise features.

This project contains the plugin's core features which are responsible for bootstrapping into Neo4j Community Edition. 

See https://github.com/dozerdb/dozerdb-plugin for the plugin build project which combines the enhanced browser and core together into the plugin.

See https://dozerdb.org for installation instructions.



## Development
Please ensure you use java 11 or above when working on the plugin.

If you would like to use an open source java version manager - please check out https://sdkman.io/
For those using sdkman - you can use the following command to switch to the favor of openjdk that we use.


### Code Formatting

Spotless is used to ensure uniform formatting. 

If you are building locally for development, you can use the following command to skip the spotless checks.

```
./mvnw clean verify -Dspotless.check.skip -Dspotless.apply.skip
```
 
## Spotless Errors

If you get any spotless errors - you can always run the following command which will format the code
properly.  Please ensure that your code has been formatted properly before submitting a pull request.

```
./mvnw spotless:apply
```

## Building
Ensure you have JDK 11+ first or you will get compile errors.

To build the project - you can run the following command:
```
./mvnw clean verify 
```
