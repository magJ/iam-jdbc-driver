# iam-jdbc-driver
A generic JDBC driver wrapper for authenticating using RDS IAM auth.

Preconfigured driver classes are provided for MySQL and PostgreSQL,  
however you could use this library to wrap any JDBC driver for use with IAM auth.

## Usage
1. Add the library to your application classpath  
    Two versions of the library are provided in maven central:
    - A regular version with transitive dependencies resolved via normal means.
    - A shaded version with the transitive dependencies bundled into the one jar file.  
    
    The latter may be more suitable for integrating with applications with which you do not have direct control over the source code/build process.

2. Ensure you also have the delegate driver loaded onto your classpath.  
   This library does not bundle any driver implementations.  
   You will need ensure that the desired MySql/Postgres/etc delegate driver class is loadable.
   
3. Configure the JDBC connection settings:  
   Depending on your application, configuration may be as simple as changing the scheme in the JDBC url.  
   


## Credentials
There are multiple ways to configure the credentials used to obtain the RDS authentication token.

### DefaultAWSCredentialsProviderChain
The default and recommended approach is to use credentials sourced from the `DefaultAWSCredentialsProviderChain`.  

Best practice would be to run your application from an instance/container/lambda  
that is configured with an instance profile, which has the appropriate `rds-db:connect` IAM actions allowed.  

However you may also specify credentials via any of the other numerous ways supported by [`DefaultAWSCredentialsProviderChain`](https://docs.aws.amazon.com/AWSJavaSDK/latest/javadoc/com/amazonaws/auth/DefaultAWSCredentialsProviderChain.html)

> - Environment Variables - AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY
> - Java System Properties - aws.accessKeyId and aws.secretKey
> - Credential profiles file at the default location (~/.aws/credentials) shared by all AWS SDKs and the AWS CLI
> - Credentials delivered through the Amazon EC2 container service if AWS_CONTAINER_CREDENTIALS_RELATIVE_URI" environment variable is set and security manager has permission to access the variable,
> - Instance profile credentials delivered through the Amazon EC2 metadata service
> - Web Identity Token credentials from the environment or container.

### Access Key ID and Secret Access Key ID
Rather than using Environment or System properties, you can pass access key as properties to the JDBC connection.  
Use of this option is discouraged, as it essentially defeats most of the purpose of using IAM auth in the first place.  
See the properties list below for more information.

### Aws Profile
You can pass the name of a profile that will be selected from the default location.  
See the properties list below for more information.

### Assume Role
You can have the driver assume a role before requesting an IAM auth token.  
The base credentials used for the assume role request are sourced from the other credential configuration options.

See the properties list below for more information.

 
## Properties

Properties for the driver wrapper can be specified either as URL query parameters or as regular JDBC connection properties.
If the same property is specified on both, then the URL query parameter will take precedence.  

All properties are passed to the delegate JDBC driver.  


The following is the list of wrapper specific properties:

|Property|Description|Example|
|---|---|---|
|`delegateJdbcDriverClass`|The JDBC driver class to delegate calls to, if not already configured. <br> Only required if using a wrapper instance with no pre-configured driver class|`com.mysql.jdbc.Driver`|
|`delegateJdbcDriverSchemeName`|The JDBC url scheme of the driver to delegate calls to. <br>This is the portion of the URL after "jdbc:" <br> For example "mysql" or "postgresql"<br> Only required if using a wrapper instance with no pre-configured driver class|`mysql`|
|`awsRegion`|The optional AWS region to use, if not specified, will use the `DefaultAwsRegionProviderChain` or a region from a configured profile|`us-east-1`|
|`awsProfile`|The optional name of an AWS profile to source credentials/region configuration from.|`default`|
|`awsStsCredentialProviderRoleArn`|An optional role ARN to assume before requesting RDS iam credentials|`arn:aws:iam::123456789012:role/DatabaseAccess`|
|`awsStsCredentialProviderSessionName`| An optional session name to use if assuming a role. A random session name will be generated if not specified.<br>This option has no effect if `awsStsCredentialProviderRoleArn` is not configured|`myapplication-123`|
|`awsStsCredentialProviderExternalId`| An optional external ID to pass in the assume role call.<br>This option has no effect if `awsStsCredentialProviderRoleArn` is not configured|`12345678-1234-1234-1234`|
|`awsAccessKeyId`|An optional AWS access key to use as credentials when requesting an RDS token.<br>This option must be configured in conjunction with `awsSecretAccessKey` or will be ignored|`AKIAIOSFODNN7EXAMPLE`|
|`awsSecretAccessKey`|An optional AWS secret key to use as credentials when requesting an RDS token.<br>This option must be configured in conjunction with `awsSecretAccessKey` or will be ignored|`wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY`|