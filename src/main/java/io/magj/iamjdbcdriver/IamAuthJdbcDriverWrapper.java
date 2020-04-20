package io.magj.iamjdbcdriver;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.auth.profile.internal.securitytoken.RoleInfo;
import com.amazonaws.auth.profile.internal.securitytoken.STSProfileCredentialsServiceProvider;
import com.amazonaws.regions.AwsProfileRegionProvider;
import com.amazonaws.regions.DefaultAwsRegionProviderChain;
import com.amazonaws.services.rds.auth.GetIamAuthTokenRequest;
import com.amazonaws.services.rds.auth.RdsIamAuthTokenGenerator;

import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public class IamAuthJdbcDriverWrapper implements Driver {

    /**
     * The JDBC driver class to delegate calls to, if not already configured.
     *
     * <p>Only required if using a wrapper instance with no pre-configured driver class
     */
    public static final String DELEGATE_DRIVER_CLASS_PROPERTY = "delegateJdbcDriverClass";
    /**
     * The JDBC url scheme of the driver to delegate calls to. This is the portion of the URL after
     * "jdbc:"
     *
     * <p>For example "mysql" or "postgresql"
     *
     * <p>Only required if using a wrapper instance with no pre-configured driver class
     */
    public static final String DELEGATE_DRIVER_SCHEME_NAME_PROPERTY =
            "delegateJdbcDriverSchemeName";
    /**
     * The optional AWS region to use, if not specified, will use the {@link
     * DefaultAwsRegionProviderChain} or a region from a configured profile
     */
    public static final String AWS_REGION_PROPERTY = "awsRegion";
    /** The optional name of an AWS profile to source credentials/region configuration from. */
    public static final String AWS_PROFILE_PROPERTY = "awsProfile";
    /** An optional role ARN to assume before requesting RDS iam credentials */
    public static final String AWS_STS_CREDENTIAL_ROLE_ARN_PROPERTY =
            "awsStsCredentialProviderRoleArn";
    /**
     * An optional session name to use if assuming a role. A random session name will be generated
     * if not specified. This option has no effect if {@link #AWS_STS_CREDENTIAL_ROLE_ARN_PROPERTY}
     * is not configured
     */
    public static final String AWS_STS_CREDENTIAL_SESSION_NAME_PROPERTY =
            "awsStsCredentialProviderSessionName";
    /**
     * An optional external ID to pass in the assume role call. This option has no effect if {@link
     * #AWS_STS_CREDENTIAL_ROLE_ARN_PROPERTY} is not configured
     */
    public static final String AWS_STS_CREDENTIAL_EXTERNAL_ID_PROPERTY =
            "awsStsCredentialProviderExternalId";
    /**
     * An optional AWS access key to use as credentials when requesting an RDS token. This option
     * must be configured in conjunction with {@link #AWS_SECRET_ACCESS_KEY_PROPERTY} or will be
     * ignored
     */
    public static final String AWS_ACCESS_KEY_ID_PROPERTY = "awsAccessKeyId";
    /**
     * An optional AWS secret key to use as credentials when requesting an RDS token. This option
     * must be configured in conjunction with {@link #AWS_ACCESS_KEY_ID_PROPERTY} or will be ignored
     */
    public static final String AWS_SECRET_ACCESS_KEY_PROPERTY = "awsSecretAccessKey";

    public static final String DEFAULT_PASSWORD_PROPERTY = "password";
    public static final String DEFAULT_USER_PROPERTY = "user";
    private static final String JDBC_URL_PREFIX = "jdbc:";

    static {
        initialiseDriverRegistration(new IamAuthJdbcDriverWrapper());
    }

    private final DefaultAwsRegionProviderChain defaultAwsRegionProviderChain =
            new DefaultAwsRegionProviderChain();
    private final DefaultAWSCredentialsProviderChain defaultAWSCredentialsProviderChain =
            DefaultAWSCredentialsProviderChain.getInstance();

    private final String wrapperSchemeName;
    private final String passwordProperty;
    private final String userProperty;
    private final Integer defaultPort;
    private final String driverClassName;

    private Driver delegate;
    private String delegateSchemeName;

    /**
     * Creates a delegateless IAM Auth JDBC wrapper.
     *
     * <p>Delegate driver class must later be configured with {@link
     * #DELEGATE_DRIVER_CLASS_PROPERTY}
     */
    public IamAuthJdbcDriverWrapper() {
        this(null, null, null, null);
    }

    public IamAuthJdbcDriverWrapper(
            String wrapperSchemeName,
            String delegateSchemeName,
            Integer defaultPort,
            String driverClassName) {
        this(
                wrapperSchemeName,
                delegateSchemeName,
                DEFAULT_PASSWORD_PROPERTY,
                DEFAULT_USER_PROPERTY,
                defaultPort,
                driverClassName);
    }

    public IamAuthJdbcDriverWrapper(
            String wrapperSchemeName,
            String delegateSchemeName,
            String passwordProperty,
            String userProperty,
            Integer defaultPort,
            String driverClassName) {
        this.wrapperSchemeName = wrapperSchemeName;
        this.delegateSchemeName = delegateSchemeName;
        this.passwordProperty = passwordProperty;
        this.userProperty = userProperty;
        this.defaultPort = defaultPort;
        this.driverClassName = driverClassName;
    }

    protected static void initialiseDriverRegistration(IamAuthJdbcDriverWrapper driver) {
        try {
            DriverManager.registerDriver(driver);
        } catch (SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static String getProperty(
            String propertyName,
            Properties connectionProperties,
            Map<String, String> uriProperties) {
        // URI properties take precedence over connection properties.
        // This is in-line with the behavior of JDBC drivers like postgres
        String property = uriProperties.get(propertyName);
        if (property == null) {
            property = connectionProperties.getProperty(propertyName);
        }
        return property;
    }

    public static Map<String, String> parseQueryString(URI uri) {
        Map<String, String> queryParams = new LinkedHashMap<>();
        String query = uri.getQuery();
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf("=");
            try {
                queryParams.put(
                        URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8.name()),
                        URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8.name()));
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            }
        }
        return queryParams;
    }

    @SuppressWarnings("unchecked")
    private static Driver resolveDriver(String driverClassName)
            throws ClassNotFoundException, NoSuchMethodException, IllegalAccessException,
                    InvocationTargetException, InstantiationException {
        Class<Driver> driverClass = (Class<Driver>) Class.forName(driverClassName);
        return driverClass.getDeclaredConstructor().newInstance();
    }

    @Override
    public boolean acceptsURL(String url) throws SQLException {
        URI parsed = parseJdbcUrl(url);
        if (isWrapperScheme(parsed)) {
            boolean isWrapperScheme = false;
            boolean isDelegateScheme = false;
            if (wrapperSchemeName != null) {
                isWrapperScheme = wrapperSchemeName.equals(parsed.getScheme());
            }
            if (delegateSchemeName != null) {
                isDelegateScheme = delegateSchemeName.equals(parsed.getScheme());
            }
            return isWrapperScheme || isDelegateScheme;
        } else if (delegate != null) {
            return delegate.acceptsURL(url);
        } else {
            return true;
        }
    }

    private boolean isWrapperScheme(URI parsed) {
        return wrapperSchemeName != null
                && parsed != null
                && wrapperSchemeName.equals(parsed.getScheme());
    }

    private String replaceScheme(String url) {
        return url.replaceFirst(Pattern.quote(wrapperSchemeName), delegateSchemeName);
    }

    private URI parseJdbcUrl(String url) {
        if (url == null || !url.startsWith(JDBC_URL_PREFIX)) {
            return null;
        }
        String substring = url.substring(JDBC_URL_PREFIX.length());
        return URI.create(substring);
    }

    @Override
    public Connection connect(String url, Properties properties) throws SQLException {
        URI parsed = parseJdbcUrl(url);
        if (parsed == null) {
            throw new SQLException("IAM auth wrapper cannot parse URL: " + url);
        }

        String host = host(parsed);
        int port = port(parsed);
        Map<String, String> uriProperties = parseQueryString(parsed);

        resolveDelegateDriver(properties, uriProperties);
        resolveDelegateSchemeName(properties, uriProperties);

        String rdsIamAuthToken = generateRdsIamAuthToken(host, port, properties, uriProperties);

        properties.setProperty(passwordProperty, rdsIamAuthToken);

        final String connectUrl = isWrapperScheme(parsed) ? replaceScheme(url) : url;

        return delegate.connect(connectUrl, properties);
    }

    private void resolveDelegateSchemeName(
            Properties connectionProperties, Map<String, String> uriProperties) {
        if (delegateSchemeName != null) {
            return;
        }
        delegateSchemeName =
                getProperty(
                        DELEGATE_DRIVER_SCHEME_NAME_PROPERTY, connectionProperties, uriProperties);
    }

    private void resolveDelegateDriver(
            Properties connectionProperties, Map<String, String> uriProperties) {
        if (delegate != null) {
            return;
        }

        String driverClassNameProperty =
                getProperty(DELEGATE_DRIVER_CLASS_PROPERTY, connectionProperties, uriProperties);
        String driverToResolve =
                driverClassNameProperty != null ? driverClassNameProperty : driverClassName;
        if (driverToResolve == null) {
            throw new IllegalStateException("No delegate JDBC driver configured");
        }
        try {
            delegate = resolveDriver(driverToResolve);
        } catch (Exception e) {
            throw new RuntimeException("Unable to load delegate JDBC driver", e);
        }
    }

    private String host(URI uri) throws SQLException {
        if (uri.getHost() != null) {
            return uri.getHost();
        } else {
            throw new SQLException(
                    "No database host specified. IAM Auth requires that a host be specified in the JDBC URL.");
        }
    }

    private int port(URI uri) throws SQLException {
        if (uri.getPort() != -1) {
            return uri.getPort();
        } else if (defaultPort != null) {
            return defaultPort;
        } else {
            throw new SQLException(
                    "No database port specified. IAM Auth requires that a port be specified in the JDBC URL.");
        }
    }

    public String generateRdsIamAuthToken(
            String host,
            int port,
            Properties connectionProperties,
            Map<String, String> uriProperties) {

        String usernameProperty = getProperty(userProperty, connectionProperties, uriProperties);
        String regionProperty =
                getProperty(AWS_REGION_PROPERTY, connectionProperties, uriProperties);
        String awsProfile = getProperty(AWS_PROFILE_PROPERTY, connectionProperties, uriProperties);

        String region = resolveRegion(regionProperty, awsProfile);

        final RdsIamAuthTokenGenerator generator =
                RdsIamAuthTokenGenerator.builder()
                        .credentials(resolveCredentialProvider(connectionProperties, uriProperties))
                        .region(region)
                        .build();

        return generator.getAuthToken(new GetIamAuthTokenRequest(host, port, usernameProperty));
    }

    private String resolveRegion(String regionProperty, String awsProfileProperty) {
        if (regionProperty != null) {
            return regionProperty;
        } else {
            if (awsProfileProperty != null) {
                AwsProfileRegionProvider awsProfileRegionProvider =
                        new AwsProfileRegionProvider(awsProfileProperty);
                if (awsProfileRegionProvider.getRegion() != null) {
                    return awsProfileRegionProvider.getRegion();
                }
            }
        }
        return defaultAwsRegionProviderChain.getRegion();
    }

    private AWSCredentialsProvider resolveCredentialProvider(
            Properties connectionProperties, Map<String, String> uriProperties) {
        String awsProfile = getProperty(AWS_PROFILE_PROPERTY, connectionProperties, uriProperties);
        String awsAccessKey =
                getProperty(AWS_ACCESS_KEY_ID_PROPERTY, connectionProperties, uriProperties);
        String awsSecretKey =
                getProperty(AWS_SECRET_ACCESS_KEY_PROPERTY, connectionProperties, uriProperties);

        final AWSCredentialsProvider baseCredentialProvider;
        if (awsAccessKey != null && awsSecretKey != null) {
            baseCredentialProvider =
                    new AWSStaticCredentialsProvider(
                            new BasicAWSCredentials(awsAccessKey, awsSecretKey));
        } else if (awsProfile != null) {
            baseCredentialProvider = new ProfileCredentialsProvider(awsProfile);
        } else {
            baseCredentialProvider = defaultAWSCredentialsProviderChain;
        }

        String assumedRole =
                getProperty(
                        AWS_STS_CREDENTIAL_ROLE_ARN_PROPERTY, connectionProperties, uriProperties);
        String externalId =
                getProperty(
                        AWS_STS_CREDENTIAL_EXTERNAL_ID_PROPERTY,
                        connectionProperties,
                        uriProperties);
        String roleSessionNameProperty =
                getProperty(
                        AWS_STS_CREDENTIAL_SESSION_NAME_PROPERTY,
                        connectionProperties,
                        uriProperties);

        if (assumedRole != null) {
            String roleSessionName =
                    roleSessionNameProperty == null
                            ? "IAM_RDS_JDBC_DRIVER_WRAPPER" + UUID.randomUUID().toString()
                            : roleSessionNameProperty;
            RoleInfo roleInfo =
                    new RoleInfo()
                            .withRoleArn(assumedRole)
                            .withLongLivedCredentialsProvider(baseCredentialProvider)
                            .withExternalId(externalId)
                            .withRoleSessionName(roleSessionName);
            return new STSProfileCredentialsServiceProvider(roleInfo);
        } else {
            return baseCredentialProvider;
        }
    }

    @Override
    public int getMajorVersion() {
        return delegate.getMajorVersion();
    }

    @Override
    public int getMinorVersion() {
        return delegate.getMinorVersion();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties properties)
            throws SQLException {
        return delegate.getPropertyInfo(url, properties);
    }

    @Override
    public boolean jdbcCompliant() {
        return delegate.jdbcCompliant();
    }
}
